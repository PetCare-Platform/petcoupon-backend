package com.mycom.petcoupon.monitoring.service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.monitoring.config.MonitoringProperties;
import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;
import com.mycom.petcoupon.monitoring.exception.MonitoringErrorCode;
import com.mycom.petcoupon.monitoring.log.MonitoringLogEventSink;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;

/**
 * 로그 발생 스레드와 SSE 송신을 분리한다. 큐가 가득 차면 이벤트만 버리고 핵심 요청은 기다리지 않는다.
 *
 * <p><b>구독 하나당 큐 하나, 송신 스레드 하나다.</b> 모든 연결을 한 워커가 순차 송신하면 느린
 * 클라이언트 하나가 나머지 전부의 이벤트를 막고(head-of-line blocking) heartbeat까지 지연시킨다.
 * 연결 수는 관리자 수만큼이고 송신 스레드는 가상 스레드라 이 정도 분리는 값이 싸다. 대신 유실도
 * 격리된다 — 큐가 넘치는 건 느린 그 클라이언트뿐이고, 다른 관리자는 이벤트를 다 받는다.
 *
 * <p>덕분에 전역 큐와 세대(generation) 카운터가 필요 없어졌다. 설정을 끄면 각 구독을 닫으면서
 * 그 구독의 큐를 비우므로, 전환 이전 이벤트가 새 상태에서 나갈 일이 없다.
 */
@Service
public class MonitoringSseService implements MonitoringLogEventSink {

    private static final String CONNECTED_EVENT = "connected";
    private static final String MONITORING_EVENT = "monitoring-event";
    private static final String HEARTBEAT_EVENT = "heartbeat";
    private static final String EVENTS_DROPPED_EVENT = "events-dropped";

    private static final String DROPPED_METRIC = "monitoring.sse.events.dropped";
    private static final String SUBSCRIPTIONS_METRIC = "monitoring.sse.subscriptions.active";

    private final MonitoringProperties properties;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean streamEnabled = new AtomicBoolean(true);
    private final AtomicLong subscriptionSequence = new AtomicLong();

    /*
     * 큐가 넘쳐 버린 이벤트는 아무 흔적도 남지 않으면 "화면에 안 뜬 에러가 있었는가"를 사후에
     * 판단할 수 없다. 구독별로 큐가 따로라 유실도 클라이언트마다 갈리므로 더 그렇다.
     */
    private final Counter droppedEvents;

    public MonitoringSseService(MonitoringProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.droppedEvents = Counter.builder(DROPPED_METRIC)
                .description("SSE 큐가 가득 차 관리자에게 전달하지 못한 monitoring 이벤트 수")
                .register(meterRegistry);

        Gauge.builder(SUBSCRIPTIONS_METRIC, subscriptions, Set::size)
                .description("현재 열려 있는 monitoring SSE 연결 수")
                .register(meterRegistry);
    }

    @Override
    public boolean isAcceptingEvents() {
        return streamEnabled.get() && !subscriptions.isEmpty();
    }

    @Override
    public void offer(MonitoringEventResponse event) {
        // isAcceptingEvents()를 통과한 뒤 상태가 바뀌었을 수 있으므로 여기서 다시 확인한다.
        if (!streamEnabled.get()) {
            return;
        }

        // ArrayBlockingQueue.offer는 즉시 실패하므로 로그를 남긴 요청 스레드를 블로킹하지 않는다.
        for (Subscription subscription : subscriptions) {
            subscription.offer(event);
        }
    }

    public SseEmitter connect() {
        /*
         * 한도 확인이 먼저다. emitter를 만든 뒤에 거절하면 버려질 객체를 괜히 만들게 된다.
         *
         * size() 확인과 add() 사이에 다른 요청이 끼어들 수 있어 정확한 상한은 아니다. 동시에
         * 들어온 요청 수만큼 잠깐 넘칠 수 있지만, 이건 연결을 안 닫는 클라이언트로부터 자원을
         * 지키려는 방어선이지 정밀한 쿼터가 아니라서 그 정도 오차는 받아들인다. 엄밀하게 하려면
         * 별도 카운터가 필요한데, 그러면 subscriptions와 진실이 두 개가 되어 close()를 한 번
         * 놓치는 순간 영구히 어긋난다.
         */
        int limit = properties.getMaxSubscriptions();
        if (limit > 0 && subscriptions.size() >= limit) {
            throw new GeneralException(MonitoringErrorCode.TOO_MANY_STREAM_CONNECTIONS);
        }

        SseEmitter emitter = createEmitter();

        if (!streamEnabled.get()) {
            sendConnectedAndClose(emitter);
            return emitter;
        }

        Subscription subscription = new Subscription(emitter);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(ignored -> subscription.close());

        subscriptions.add(subscription);
        subscription.start();

        /*
         * add()와 OFF 전환이 겹치면 "이미 emitters를 순회해 닫은 뒤"에 등록되어 꺼진 상태인데
         * 살아있는 연결이 남는다. 등록 후 한 번 더 확인하면 둘 중 하나는 반드시 닫는다 —
         * 전환 쪽이 우리를 보거나(집합에 이미 있으므로), 우리가 꺼진 플래그를 보거나.
         */
        if (!streamEnabled.get()) {
            subscription.close();
        }

        return emitter;
    }

    public boolean isStreamEnabled() {
        return streamEnabled.get();
    }

    public void setStreamEnabled(boolean enabled) {
        if (streamEnabled.getAndSet(enabled) == enabled) {
            return;
        }

        if (!enabled) {
            subscriptions.forEach(Subscription::close);
        }
    }

    @PreDestroy
    void shutdown() {
        subscriptions.forEach(Subscription::close);
    }

    // 테스트가 느린 클라이언트나 끊긴 연결을 흉내 낼 수 있도록 생성 지점만 분리한다.
    SseEmitter createEmitter() {
        return new SseEmitter(properties.getEmitterTimeout().toMillis());
    }

    int activeSubscriptionCount() {
        return subscriptions.size();
    }

    private void sendConnectedAndClose(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name(CONNECTED_EVENT)
                    .data(new ConnectedResponse(false), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
            // 연결 자체가 이미 끊긴 경우에도 monitoring 기능이 요청 처리에 영향을 주지 않는다.
        } finally {
            emitter.complete();
        }
    }

    private long heartbeatIntervalMillis() {
        Duration interval = properties.getHeartbeatInterval();
        return interval == null ? 0L : interval.toMillis();
    }

    /**
     * 관리자 연결 하나. 자기 큐와 자기 송신 스레드를 가진다.
     */
    private final class Subscription {

        private final SseEmitter emitter;
        private final BlockingQueue<MonitoringEventResponse> queue;
        private final long heartbeatMillis;
        private final AtomicBoolean active = new AtomicBoolean(true);

        // 이 연결이 놓친 건수. 다음 송신 기회에 클라이언트로 알리고 0으로 되돌린다.
        private final AtomicLong droppedSinceNotice = new AtomicLong();

        private volatile Thread sender;

        private Subscription(SseEmitter emitter) {
            this.emitter = emitter;
            this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity()));
            this.heartbeatMillis = heartbeatIntervalMillis();
        }

        private void start() {
            sender = Thread.ofVirtual()
                    .name("monitoring-sse-" + subscriptionSequence.incrementAndGet())
                    .start(this::run);
        }

        private void offer(MonitoringEventResponse event) {
            // 이 구독의 큐가 가득 차면 이 관리자만 이벤트를 놓친다. 다른 연결은 영향이 없다.
            if (!queue.offer(event)) {
                droppedEvents.increment();
                droppedSinceNotice.incrementAndGet();
            }
        }

        /*
         * 송신은 전부 이 스레드에서만 한다. SseEmitter는 동시 송신에 안전하지 않아서 heartbeat를
         * 별도 스케줄러로 보내면 이벤트 송신과 겹칠 때 SSE 프레임이 깨진다. take() 대신
         * poll(timeout)을 쓰면 스레드를 더 늘리지 않고도 "이 연결이 유휴하면 heartbeat"가 된다.
         */
        private void run() {
            if (!send(CONNECTED_EVENT, new ConnectedResponse(true))) {
                return;
            }

            while (active.get()) {
                try {
                    MonitoringEventResponse event = heartbeatMillis > 0
                            ? queue.poll(heartbeatMillis, TimeUnit.MILLISECONDS)
                            : queue.take();

                    // 밀린 게 풀린 지금이 유실을 알릴 첫 기회다. 이벤트보다 먼저 보내야
                    // 화면에서 "여기서 N건이 빠졌다"는 순서가 맞는다.
                    if (!notifyDroppedIfAny()) {
                        return;
                    }

                    // poll 타임아웃이면 null이다 — 이 연결이 heartbeat 간격만큼 유휴했다는 뜻.
                    boolean delivered = event != null
                            ? send(MONITORING_EVENT, event)
                            : send(HEARTBEAT_EVENT, new HeartbeatResponse(LocalDateTime.now()));

                    if (!delivered) {
                        return;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private boolean notifyDroppedIfAny() {
            long dropped = droppedSinceNotice.getAndSet(0);
            return dropped == 0 || send(EVENTS_DROPPED_EVENT, new DroppedResponse(dropped));
        }

        private boolean send(String eventName, Object payload) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(payload, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException exception) {
                // 끊긴 연결. 이 구독만 정리하고 나머지는 그대로 둔다.
                close();
                return false;
            }
        }

        /*
         * emitter.complete()가 onCompletion 콜백을 통해 이 메서드를 다시 부르므로 재진입한다.
         * compareAndSet으로 첫 호출만 실제 정리를 수행한다.
         */
        private void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }

            subscriptions.remove(this);
            queue.clear();

            Thread currentSender = sender;
            if (currentSender != null && currentSender != Thread.currentThread()) {
                currentSender.interrupt();
            }

            emitter.complete();
        }
    }

    private record ConnectedResponse(boolean streamEnabled) {
    }

    /*
     * 이 연결이 큐 포화로 놓친 건수. 관리자가 "빠짐없이 다 봤다"고 오해하지 않게 화면에
     * "N건 누락됨"을 띄울 수 있도록 알린다. 전체 유실량은 monitoring.sse.events.dropped 지표로 본다.
     */
    private record DroppedResponse(long droppedCount) {
    }

    /*
     * SSE 명세상 data 필드가 비어 있으면 브라우저가 이벤트를 dispatch하지 않는다. 즉 이름만 있는
     * heartbeat는 프론트에서 관측되지 않으므로 최소 payload를 함께 보낸다. 프론트는 이 값으로
     * "마지막 수신 시각"을 갱신해 연결이 죽었는지 판단할 수 있다.
     */
    private record HeartbeatResponse(LocalDateTime sentAt) {
    }
}
