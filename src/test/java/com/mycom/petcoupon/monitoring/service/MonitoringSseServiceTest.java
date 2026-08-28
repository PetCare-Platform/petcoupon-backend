package com.mycom.petcoupon.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.monitoring.config.MonitoringProperties;
import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;
import com.mycom.petcoupon.monitoring.exception.MonitoringErrorCode;

/**
 * MonitoringSseService의 상태 전이와 장애 상황 동작 검증.
 *
 * <p>실제 SSE wire format은 {@code MonitoringSseHeartbeatTest}가 MockMvc로 검증한다. 여기서는
 * 느린 클라이언트나 끊긴 연결처럼 servlet 스택으로는 재현할 수 없는 상황을 다루므로,
 * {@code createEmitter()}를 오버라이드해 송신을 가로채는 emitter를 주입한다.
 */
class MonitoringSseServiceTest {

    private static final String CONNECTED = "connected";
    private static final String MONITORING_EVENT = "monitoring-event";
    private static final String HEARTBEAT = "heartbeat";
    private static final String EVENTS_DROPPED = "events-dropped";

    private final List<RecordingEmitter> created = new CopyOnWriteArrayList<>();

    private TestableMonitoringSseService service;
    private SimpleMeterRegistry meterRegistry;

    // 다음에 만들어질 emitter가 멈춰 설 이벤트. 연결 후에 걸면 송신 스레드가 이미 connected를
    // 보내버린 뒤라 경쟁이 생기므로, 생성 시점에 미리 심어 둔다.
    private volatile String blockNextOn;

    @AfterEach
    void tearDown() {
        // 막아둔 송신을 먼저 풀어야 shutdown이 송신 스레드를 정리할 수 있다.
        created.forEach(RecordingEmitter::unblock);
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    @DisplayName("연결하면 connected(true)를 보낸다")
    void sendsConnectedTrueOnConnect() {
        start(properties());

        RecordingEmitter emitter = connect();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.names()).containsExactly(CONNECTED));
        assertThat(emitter.payloads()).anyMatch(payload -> payload.contains("streamEnabled=true"));
        assertThat(service.activeSubscriptionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("스트림이 꺼져 있어도 연결을 닫지 않고 connected(false)만 보낸다")
    void keepsConnectionOpenWhenDisabled() {
        start(properties());
        service.setStreamEnabled(false);

        RecordingEmitter emitter = connect();

        /*
         * 닫으면 EventSource가 기본 3초 간격으로 재연결을 반복한다 — 꺼둔 내내 폭주한다.
         * 연결을 유지해야 재연결 자체가 없어진다.
         */
        awaitConnected(emitter);
        assertThat(emitter.payloads()).anyMatch(payload -> payload.contains("streamEnabled=false"));
        assertThat(emitter.isCompleted()).isFalse();
        assertThat(service.activeSubscriptionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("스트림을 꺼도 연결은 유지되고 이후 이벤트만 전달되지 않는다")
    void keepsSubscriptionsButStopsEventsWhenDisabled() {
        start(properties());
        RecordingEmitter emitter = connect();
        awaitConnected(emitter);

        service.setStreamEnabled(false);

        assertThat(emitter.isCompleted()).isFalse();
        assertThat(service.activeSubscriptionCount()).isEqualTo(1);

        service.offer(event("전환 이후 이벤트"));

        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.names()).doesNotContain(MONITORING_EVENT));
    }

    @Test
    @DisplayName("ON/OFF가 바뀌면 열려 있는 연결에 바뀐 상태를 알린다")
    void notifiesOpenSubscriptionsWhenStreamStateChanges() {
        MonitoringProperties properties = properties();
        properties.setHeartbeatInterval(Duration.ofMillis(100));
        start(properties);

        RecordingEmitter emitter = connect();
        awaitConnected(emitter);

        // 다른 관리자가 껐을 때도 내 화면이 최신 상태를 반영해야 한다.
        service.setStreamEnabled(false);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(emitter.payloads()).anyMatch(payload -> payload.contains("streamEnabled=false")));
    }

    @Test
    @DisplayName("다시 켜면 재접속 없이 이벤트가 이어진다")
    void resumesEventsAfterReEnableWithoutReconnect() {
        start(properties());
        RecordingEmitter emitter = connect();
        awaitConnected(emitter);

        service.setStreamEnabled(false);
        service.offer(event("꺼진 동안 발생"));
        service.setStreamEnabled(true);
        service.offer(event("다시 켠 뒤 발생"));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.names()).contains(MONITORING_EVENT));

        // 꺼져 있던 동안의 이벤트가 뒤늦게 섞여 나오면 안 된다.
        assertThat(emitter.payloads().stream().filter(p -> p.contains("발생")).toList())
                .noneMatch(payload -> payload.contains("꺼진 동안 발생"));
        assertThat(emitter.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("connect와 OFF 전환이 동시에 일어나도 꺼진 상태의 이벤트는 나가지 않는다")
    void deliversNoEventsWhenConnectRacesWithDisable() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            start(properties());

            CountDownLatch startGate = new CountDownLatch(1);
            Thread connector = new Thread(() -> {
                awaitQuietly(startGate);
                service.connect();
            });
            Thread toggler = new Thread(() -> {
                awaitQuietly(startGate);
                service.setStreamEnabled(false);
            });

            connector.start();
            toggler.start();
            startGate.countDown();
            connector.join();
            toggler.join();

            // 연결은 남아도 된다(이제 닫지 않는다). 꺼진 상태에서 이벤트가 새지 않는 게 핵심이다.
            assertThat(service.isStreamEnabled()).isFalse();
            service.offer(event("꺼진 상태 이벤트"));

            for (RecordingEmitter emitter : created) {
                assertThat(emitter.names())
                        .as("시도 %d에서 꺼진 상태인데 이벤트가 나갔다", attempt)
                        .doesNotContain(MONITORING_EVENT);
            }

            tearDown();
        }
    }

    @Test
    @DisplayName("이벤트를 전달하고 끊어진 연결은 제거한다")
    void broadcastsAndDropsBrokenSubscription() {
        start(properties());
        RecordingEmitter healthy = connect();
        RecordingEmitter broken = connect();
        awaitConnected(healthy);
        awaitConnected(broken);

        broken.failWith(new IOException("broken pipe"));
        service.offer(event("발급 실패"));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(healthy.names()).contains(MONITORING_EVENT));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(broken.isCompleted()).isTrue();
            assertThat(service.activeSubscriptionCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("heartbeat 송신이 실패하면 해당 연결을 정리한다")
    void dropsSubscriptionWhenHeartbeatFails() {
        MonitoringProperties properties = properties();
        properties.setHeartbeatInterval(Duration.ofMillis(100));
        start(properties);

        RecordingEmitter emitter = connect();
        awaitConnected(emitter);

        // 이벤트가 없어도 heartbeat가 나가므로, 끊긴 연결이 다음 주기에 정리된다.
        emitter.failWith(new IOException("broken pipe"));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(emitter.isCompleted()).isTrue();
            assertThat(service.activeSubscriptionCount()).isZero();
        });
    }

    @Test
    @DisplayName("큐가 가득 차도 로그를 남긴 스레드는 블로킹되지 않는다")
    void neverBlocksTheLoggingThread() {
        MonitoringProperties properties = properties();
        properties.setQueueCapacity(1);
        start(properties);

        // connected 송신에서 멈춰 세우면 큐가 전혀 소비되지 않아 곧바로 가득 찬다.
        RecordingEmitter stalled = connectBlockedOn(CONNECTED);
        await().atMost(Duration.ofSeconds(2)).until(stalled::isBlocked);

        long startedAt = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            service.offer(event("발급 실패 " + i));
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("느린 클라이언트가 다른 클라이언트의 이벤트 전달을 지연시키지 않는다")
    void slowSubscriberDoesNotDelayOthers() {
        MonitoringProperties properties = properties();
        properties.setQueueCapacity(1);
        start(properties);

        RecordingEmitter slow = connectBlockedOn(CONNECTED);
        await().atMost(Duration.ofSeconds(2)).until(slow::isBlocked);

        RecordingEmitter fast = connect();
        awaitConnected(fast);

        service.offer(event("발급 실패"));

        // 느린 쪽이 connected에서 멈춰 있는 동안에도 빠른 쪽은 정상 수신해야 한다.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(fast.names()).contains(MONITORING_EVENT));
        assertThat(slow.names()).doesNotContain(MONITORING_EVENT);
    }

    @Test
    @DisplayName("동시 연결 한도를 넘으면 503으로 거절한다")
    void rejectsConnectionsBeyondLimit() {
        MonitoringProperties properties = properties();
        properties.setMaxSubscriptions(2);
        start(properties);

        connect();
        connect();

        assertThatThrownBy(() -> service.connect())
                .isInstanceOf(GeneralException.class)
                .extracting(exception -> ((GeneralException) exception).getErrorCode())
                .isEqualTo(MonitoringErrorCode.TOO_MANY_STREAM_CONNECTIONS);

        assertThat(service.activeSubscriptionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("연결이 닫히면 그 자리를 다시 쓸 수 있다")
    void freesSlotWhenSubscriptionCloses() {
        MonitoringProperties properties = properties();
        properties.setMaxSubscriptions(1);
        start(properties);

        RecordingEmitter first = connect();
        awaitConnected(first);

        // 끊긴 연결이 정리되면 한도에 다시 여유가 생겨야 한다.
        first.failWith(new IOException("broken pipe"));
        service.offer(event("발급 실패"));
        await().atMost(Duration.ofSeconds(2)).until(() -> service.activeSubscriptionCount() == 0);

        RecordingEmitter second = connect();
        awaitConnected(second);
        assertThat(service.activeSubscriptionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("한도를 0 이하로 두면 제한하지 않는다")
    void allowsUnlimitedConnectionsWhenLimitIsZero() {
        MonitoringProperties properties = properties();
        properties.setMaxSubscriptions(0);
        start(properties);

        for (int i = 0; i < 5; i++) {
            connect();
        }

        assertThat(service.activeSubscriptionCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("큐가 넘쳐 버린 이벤트는 지표로 집계된다")
    void countsDroppedEvents() {
        MonitoringProperties properties = properties();
        properties.setQueueCapacity(1);
        start(properties);

        RecordingEmitter stalled = connectBlockedOn(CONNECTED);
        await().atMost(Duration.ofSeconds(2)).until(stalled::isBlocked);

        for (int i = 0; i < 10; i++) {
            service.offer(event("발급 실패 " + i));
        }

        // 큐 용량 1이므로 첫 건만 들어가고 나머지 9건은 유실된다.
        assertThat(droppedCount()).isEqualTo(9.0);
        assertThat(activeSubscriptionGauge()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("밀렸다 풀리면 누락 건수를 클라이언트에 알린다")
    void notifiesClientAboutDroppedEvents() {
        MonitoringProperties properties = properties();
        properties.setQueueCapacity(1);
        start(properties);

        RecordingEmitter emitter = connectBlockedOn(CONNECTED);
        await().atMost(Duration.ofSeconds(2)).until(emitter::isBlocked);

        for (int i = 0; i < 5; i++) {
            service.offer(event("발급 실패 " + i));
        }

        // 막아둔 송신을 풀면 밀린 큐를 소비하면서 유실을 먼저 알려야 한다.
        emitter.unblock();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.names()).contains(EVENTS_DROPPED));
        assertThat(emitter.payloads()).anyMatch(payload -> payload.contains("droppedCount=4"));

        // 누락 통지는 실제 이벤트보다 먼저 나가야 화면 순서가 맞는다.
        List<String> names = emitter.names();
        assertThat(names.indexOf(EVENTS_DROPPED)).isLessThan(names.indexOf(MONITORING_EVENT));
    }

    @Test
    @DisplayName("유실이 없으면 누락 통지를 보내지 않는다")
    void doesNotNotifyWhenNothingDropped() {
        start(properties());
        RecordingEmitter emitter = connect();
        awaitConnected(emitter);

        service.offer(event("발급 실패"));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.names()).contains(MONITORING_EVENT));
        assertThat(emitter.names()).doesNotContain(EVENTS_DROPPED);
        assertThat(droppedCount()).isZero();
    }

    private MonitoringProperties properties() {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setQueueCapacity(100);
        properties.setEmitterTimeout(Duration.ofMinutes(1));
        // 대부분의 시나리오는 heartbeat와 무관하므로 기본은 꺼 둔다.
        properties.setHeartbeatInterval(Duration.ZERO);
        return properties;
    }

    private void start(MonitoringProperties properties) {
        created.clear();
        meterRegistry = new SimpleMeterRegistry();
        service = new TestableMonitoringSseService(properties, meterRegistry);
    }

    private double droppedCount() {
        return meterRegistry.get("monitoring.sse.events.dropped").counter().count();
    }

    private double activeSubscriptionGauge() {
        return meterRegistry.get("monitoring.sse.subscriptions.active").gauge().value();
    }

    private RecordingEmitter connect() {
        return (RecordingEmitter) service.connect();
    }

    private RecordingEmitter connectBlockedOn(String eventName) {
        blockNextOn = eventName;
        return connect();
    }

    private void awaitConnected(RecordingEmitter emitter) {
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.names()).contains(CONNECTED));
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private MonitoringEventResponse event(String message) {
        return new MonitoringEventResponse(
                UUID.randomUUID().toString(),
                "ERROR",
                "CouponIssueStreamConsumer",
                message,
                "IllegalStateException",
                LocalDateTime.now()
        );
    }

    private final class TestableMonitoringSseService extends MonitoringSseService {

        private TestableMonitoringSseService(MonitoringProperties properties, MeterRegistry meterRegistry) {
            super(properties, meterRegistry);
        }

        @Override
        SseEmitter createEmitter() {
            RecordingEmitter emitter = new RecordingEmitter();
            if (blockNextOn != null) {
                emitter.blockOn(blockNextOn);
                blockNextOn = null;
            }
            created.add(emitter);
            return emitter;
        }
    }

    /**
     * 송신을 가로채는 SseEmitter. servlet 응답 없이도 무엇이 나갔는지 볼 수 있고, 특정 이벤트에서
     * 멈추거나(느린 클라이언트) 예외를 던지게(끊긴 연결) 만들 수 있다.
     */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> names = new CopyOnWriteArrayList<>();
        private final List<String> payloads = new CopyOnWriteArrayList<>();
        private final CountDownLatch releaseGate = new CountDownLatch(1);
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean blocked = new AtomicBoolean();

        private volatile String blockOnEvent;
        private volatile IOException failure;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            String name = "(unnamed)";
            String payload = "";

            /*
             * Spring은 이름과 뒤따르는 "data:" 접두사를 한 문자열로 묶어 넘긴다
             * (예: "event:heartbeat\ndata:"). 그래서 첫 줄바꿈까지만 잘라야 이름이 나온다.
             * payload는 MediaType이 붙은 별도 항목으로 들어온다.
             */
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                Object data = item.getData();
                if (data instanceof String text) {
                    if (text.startsWith("event:")) {
                        int lineEnd = text.indexOf('\n');
                        name = lineEnd >= 0
                                ? text.substring("event:".length(), lineEnd)
                                : text.substring("event:".length());
                    }
                } else {
                    payload = String.valueOf(data);
                }
            }

            if (name.equals(blockOnEvent)) {
                blocked.set(true);
                try {
                    releaseGate.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while blocked", exception);
                }
            }

            IOException current = failure;
            if (current != null) {
                throw current;
            }

            names.add(name);
            payloads.add(payload);
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        private List<String> names() {
            return new ArrayList<>(names);
        }

        private List<String> payloads() {
            return new ArrayList<>(payloads);
        }

        private boolean isCompleted() {
            return completed.get();
        }

        private boolean isBlocked() {
            return blocked.get();
        }

        private void blockOn(String eventName) {
            this.blockOnEvent = eventName;
        }

        private void failWith(IOException exception) {
            this.failure = exception;
        }

        private void unblock() {
            releaseGate.countDown();
        }
    }
}
