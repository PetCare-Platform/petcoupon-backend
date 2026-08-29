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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    // 가장 최근에 만들어진 구독의 큐. 자리를 빼앗기는 상황을 테스트에서 걸기 위해 붙잡아 둔다.
    private volatile SlotStealingQueue stealingQueue;

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
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(service.activeSubscriptionCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("송신에 실패해도 emitter를 완료시키지 않는다")
    void doesNotCompleteEmitterAfterSendFailure() {
        start(properties());
        RecordingEmitter emitter = connect();
        awaitConnected(emitter);

        emitter.failWith(new IOException("broken pipe"));
        service.offer(event("발급 실패"));

        // 구독은 정리되어야 한다.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(service.activeSubscriptionCount()).isZero());

        /*
         * 여기서 complete()를 부르면 이미 못 쓰는 응답을 flush하면서
         * AsyncRequestNotUsableException이 나고, 그게 async dispatch로 GlobalExceptionHandler까지
         * 올라가 JSON 500을 다시 쓰려다 HttpMessageNotWritableException으로 이어진다(#191).
         * 송신 실패는 컨테이너가 이미 아는 사건이라 요청 정리도 컨테이너가 한다.
         */
        assertThat(emitter.isCompleted()).isFalse();
        assertThat(emitter.completeCount()).isZero();
    }

    @Test
    @DisplayName("컨테이너 콜백으로 정리된 뒤에도 emitter를 완료시키지 않는다")
    void doesNotCompleteEmitterOnContainerCallbacks() {
        start(properties());
        RecordingEmitter emitter = connect();
        awaitConnected(emitter);

        // 컨테이너가 연결 종료를 통보한 상황. 이때 complete()를 부르면 dispatch가 한 번 더 돈다.
        emitter.fireCompletion();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(service.activeSubscriptionCount()).isZero());
        assertThat(emitter.completeCount()).isZero();
    }

    @Test
    @DisplayName("서버 종료 시에는 살아 있는 연결만 한 번씩 완료시킨다")
    void completesLiveEmittersOnlyOnShutdown() {
        start(properties());
        RecordingEmitter live = connect();
        RecordingEmitter dead = connect();
        awaitConnected(live);
        awaitConnected(dead);

        // dead는 송신 실패로 이미 정리된 구독이다.
        dead.failWith(new IOException("broken pipe"));
        service.offer(event("발급 실패"));
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(service.activeSubscriptionCount()).isEqualTo(1));

        service.shutdown();

        // 살아 있던 연결은 정확히 한 번 완료되고, 이미 끝난 연결은 다시 완료되지 않는다.
        assertThat(live.completeCount()).isEqualTo(1);
        assertThat(dead.completeCount()).isZero();

        // 두 번 내려도 중복 완료가 없어야 한다.
        service.shutdown();
        assertThat(live.completeCount()).isEqualTo(1);
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
            assertThat(service.activeSubscriptionCount()).isZero();
            assertThat(emitter.completeCount()).isZero();
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
    @DisplayName("큐가 가득 차면 오래된 이벤트를 버리고 최신 이벤트를 남긴다")
    void keepsNewestEventsWhenQueueIsFull() {
        MonitoringProperties properties = properties();
        properties.setQueueCapacity(3);
        start(properties);

        RecordingEmitter emitter = connectBlockedOn(CONNECTED);
        await().atMost(Duration.ofSeconds(2)).until(emitter::isBlocked);

        for (int i = 0; i < 10; i++) {
            service.offer(event("발급 실패 " + i));
        }

        emitter.unblock();

        /*
         * 부하 상황에서 관리자가 봐야 하는 건 지금 무슨 일이 나고 있는지다. 최신을 버리면
         * 화면이 과거에 묶여 장애가 커질수록 현재 상태를 못 본다.
         */
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(emitter.payloads())
                        .anyMatch(payload -> payload.contains("발급 실패 9")));

        List<String> delivered = emitter.payloads().stream()
                .filter(payload -> payload.contains("발급 실패"))
                .toList();

        assertThat(delivered).hasSize(3);
        assertThat(delivered).allMatch(payload ->
                payload.contains("발급 실패 7")
                        || payload.contains("발급 실패 8")
                        || payload.contains("발급 실패 9"));

        // 10건 중 3건만 남았으니 유실은 정확히 7건이다.
        assertThat(droppedCount()).isEqualTo(7.0);
        assertThat(emitter.payloads()).anyMatch(payload -> payload.contains("droppedCount=7"));
    }

    @Test
    @DisplayName("비운 자리를 다른 생산자가 채가도 마지막까지 최신 이벤트 삽입을 재시도한다")
    void retriesInsertAfterEveryPollWhenSlotsAreStolen() {
        int capacity = 3;
        MonitoringProperties properties = properties();
        properties.setQueueCapacity(capacity);
        start(properties);

        RecordingEmitter emitter = connectBlockedOn(CONNECTED);
        await().atMost(Duration.ofSeconds(2)).until(emitter::isBlocked);

        // 큐를 가득 채운다.
        for (int i = 0; i < capacity; i++) {
            service.offer(event("오래된 " + i));
        }

        /*
         * 이후 3번의 offer를 "다른 로깅 스레드가 자리를 먼저 채간 것"으로 만든다.
         * 재시도 횟수와 같은 수라, 루프의 마지막 poll이 만든 자리에서만 삽입이 성공할 수 있다.
         *
         * 예전 구조(offer 먼저, 실패하면 poll)에서는 마지막 poll 뒤에 offer가 없어서
         * 오래된 3건을 버리고도 최신 이벤트까지 잃고 큐에 빈자리가 남았다 — 4건 유실.
         * 지금 구조(poll 먼저, 곧바로 offer)에서는 3건만 버리고 최신 이벤트가 들어간다.
         */
        stealingQueue.stealNext(3);
        service.offer(event("최신 이벤트"));

        emitter.unblock();

        // 최신 이벤트는 반드시 살아남아야 한다.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(deliveredEvents(emitter))
                        .anyMatch(payload -> payload.contains("최신 이벤트")));

        // 버린 건 오래된 3건뿐이다. 4건이면 최신 이벤트까지 버렸다는 뜻이다.
        assertThat(droppedCount()).isEqualTo(3.0);
        assertThat(deliveredEvents(emitter)).hasSize(1);
    }

    @Test
    @DisplayName("여러 스레드가 동시에 넣어도 큐에 빈자리가 남지 않고 최신 이벤트가 살아남는다")
    void keepsQueueFullAndNewestEventsUnderConcurrentProducers() throws Exception {
        int capacity = 4;
        int producers = 8;
        int perProducer = 500;

        MonitoringProperties properties = properties();
        properties.setQueueCapacity(capacity);
        start(properties);

        // 송신을 막아 두면 큐가 전혀 소비되지 않아, 남은 내용이 곧 offer 로직의 결과가 된다.
        RecordingEmitter emitter = connectBlockedOn(CONNECTED);
        await().atMost(Duration.ofSeconds(2)).until(emitter::isBlocked);

        CountDownLatch startGate = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int producer = p;
            threads.add(new Thread(() -> {
                awaitQuietly(startGate);
                for (int i = 0; i < perProducer; i++) {
                    service.offer(event("p" + producer + "-" + i));
                }
            }));
        }

        threads.forEach(Thread::start);
        startGate.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        /*
         * 생산이 멈춘 뒤 단일 스레드로 표식을 큐 용량만큼 넣는다. drop-oldest가 맞다면 이 표식이
         * 마지막 capacity건이므로 전부 살아남아야 한다 — 동시 생산 구간에서는 어떤 이벤트가
         * 남을지 결정적으로 말할 수 없어서, 최신 우선 여부는 이 조용한 구간으로 검증한다.
         */
        for (int i = 0; i < capacity; i++) {
            service.offer(event("MARKER-" + i));
        }

        emitter.unblock();

        long total = (long) producers * perProducer + capacity;

        /*
         * 큐에 남아 있던 건 정확히 capacity건이어야 한다.
         *
         * 이게 이 테스트의 핵심이다. poll로 자리를 만들어 놓고 offer를 재시도하지 않으면 그 자리가
         * 빈 채로 남아, 소비 시점에 capacity보다 적게 나온다. 단일 생산자로는 재시도 한 번에
         * 성공해 버려서 이 경쟁 조건 자체가 재현되지 않는다.
         */
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(deliveredEvents(emitter)).hasSize(capacity));

        // 유실 집계는 정확해야 한다 — 전달된 것과 버린 것의 합이 넣은 전부다.
        assertThat(droppedCount()).isEqualTo((double) (total - capacity));

        // 마지막에 넣은 최신 이벤트가 살아남았다.
        assertThat(deliveredEvents(emitter)).allMatch(payload -> payload.contains("MARKER-"));
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

    // 실제로 클라이언트에 나간 monitoring-event의 payload만 추린다.
    // connected/heartbeat/events-dropped는 제외된다.
    private List<String> deliveredEvents(RecordingEmitter emitter) {
        List<String> names = emitter.names();
        List<String> payloads = emitter.payloads();
        List<String> delivered = new ArrayList<>();

        for (int i = 0; i < names.size() && i < payloads.size(); i++) {
            if (MONITORING_EVENT.equals(names.get(i))) {
                delivered.add(payloads.get(i));
            }
        }

        return delivered;
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

        @Override
        BlockingQueue<MonitoringEventResponse> createQueue(int capacity) {
            stealingQueue = new SlotStealingQueue(capacity);
            return stealingQueue;
        }
    }

    /**
     * 지정한 횟수만큼 {@code offer}를 실패시키는 큐.
     *
     * <p>drop-oldest가 재시도하는 이유는 "poll로 만든 자리를 다른 로깅 스레드가 먼저 채가는" 경쟁
     * 때문인데, 그 순간은 스레드를 아무리 많이 띄워도 결정적으로 만들 수 없다. 실패하는 offer는
     * 자리를 빼앗긴 것과 호출자 입장에서 구별되지 않으므로, 그 한 지점만 흉내 낸다.
     */
    private static final class SlotStealingQueue extends ArrayBlockingQueue<MonitoringEventResponse> {

        private final AtomicInteger remainingSteals = new AtomicInteger();

        private SlotStealingQueue(int capacity) {
            super(capacity);
        }

        @Override
        public boolean offer(MonitoringEventResponse event) {
            if (remainingSteals.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                return false;
            }
            return super.offer(event);
        }

        private void stealNext(int count) {
            remainingSteals.set(count);
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
        private final AtomicBoolean blocked = new AtomicBoolean();

        // 서블릿 컨테이너가 연결 종료를 통보하는 경로를 테스트에서 흉내 내기 위해 콜백을 붙잡아 둔다.
        private final List<Runnable> completionCallbacks = new CopyOnWriteArrayList<>();

        /*
         * 완료 횟수를 센다. boolean이면 "complete를 아예 안 불렀다"와 "여러 번 불렀다"를
         * 구분할 수 없는데, #191에서 문제가 된 게 정확히 그 구분이다.
         */
        private final AtomicInteger completeCount = new AtomicInteger();

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
            completeCount.incrementAndGet();
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallbacks.add(callback);
            super.onCompletion(callback);
        }

        private List<String> names() {
            return new ArrayList<>(names);
        }

        private List<String> payloads() {
            return new ArrayList<>(payloads);
        }

        private boolean isCompleted() {
            return completeCount.get() > 0;
        }

        private int completeCount() {
            return completeCount.get();
        }

        // 컨테이너가 onCompletion을 통보한 상황을 재현한다.
        private void fireCompletion() {
            completionCallbacks.forEach(Runnable::run);
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
