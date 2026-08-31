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

    /*
     * drop-oldest 재시도 횟수. poll()로 만든 자리를 다른 로깅 스레드가 먼저 가져갈 수 있어
     * 한 번으로는 부족하지만, 무한 재시도는 로그를 남긴 요청 스레드를 붙잡는다. 몇 번 양보하고
     * 그래도 안 되면 이번 이벤트를 버린다 — 어차피 버려야 할 만큼 밀린 상황이다.
     */
    private static final int OFFER_ATTEMPTS = 3;

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

        /*
         * 스트림이 꺼져 있어도 연결은 연다.
         *
         * 예전에는 connected(false)를 보내고 바로 닫았는데, EventSource 계열 클라이언트는 끊기면
         * 기본 3초 간격으로 자동 재연결한다. 즉 설정을 꺼두는 내내 관리자 화면마다 3초에 한 번씩
         * 이 엔드포인트를 두드리고, 그때마다 세션 검증(Redis 조회)까지 돌았다. 연결을 유지하면
         * 재연결 자체가 사라지고, 설정을 다시 켤 때 재접속 없이 곧바로 이벤트가 흐른다.
         */
        SseEmitter emitter = createEmitter();

        /*
         * 세 콜백 모두 "컨테이너가 이미 이 요청을 끝내고 있다"는 통보다. 여기서 할 일은 구독 정리뿐이고
         * emitter.complete()를 불러선 안 된다 — 그건 응답을 flush하고 async dispatch를 한 번 더
         * 일으키는데, 이미 끊긴 응답에서는 AsyncRequestNotUsableException으로 돌아온다(#191).
         * ResponseBodyEmitter#complete javadoc도 컨테이너 이벤트 뒤에는 쓰지 말라고 명시한다.
         */
        Subscription subscription = new Subscription(emitter);
        emitter.onCompletion(subscription::detach);
        emitter.onTimeout(subscription::detach);
        emitter.onError(ignored -> subscription.detach());

        subscriptions.add(subscription);
        subscription.start();

        return emitter;
    }

    public boolean isStreamEnabled() {
        return streamEnabled.get();
    }

    public void setStreamEnabled(boolean enabled) {
        if (streamEnabled.getAndSet(enabled) == enabled) {
            return;
        }

        /*
         * 연결은 닫지 않는다(위 connect 주석 참고). 대신 두 가지를 한다.
         *
         * 1. 끄는 경우 큐에 남은 이벤트를 버린다. 다시 켰을 때 전환 이전 이벤트가 뒤늦게 나가면
         *    관리자가 보는 시각과 실제 발생 시각이 어긋난다. 버린 뒤에도 offer가 끼어들 수 있어서
         *    송신 직전에 한 번 더 streamEnabled를 확인한다(Subscription#run).
         * 2. 바뀐 상태를 각 연결에 알린다. 다른 관리자가 끈 경우에도 화면이 최신 상태를 반영해야 한다.
         */
        subscriptions.forEach(subscription -> subscription.onStreamStateChanged(enabled));
    }

    /*
     * 애플리케이션이 주도하는 유일한 정상 종료 경로다. 여기서만 emitter.complete()를 부른다 —
     * 아직 살아 있는 연결에 "서버가 내려간다"고 알리고 async 요청을 정리해야 하기 때문이다.
     * 나머지 종료 경로(송신 실패, 컨테이너 콜백)는 detach만 한다.
     */
    @PreDestroy
    void shutdown() {
        subscriptions.forEach(Subscription::complete);
    }

    // 테스트가 느린 클라이언트나 끊긴 연결을 흉내 낼 수 있도록 생성 지점만 분리한다.
    SseEmitter createEmitter() {
        return new SseEmitter(properties.getEmitterTimeout().toMillis());
    }

    /*
     * 위와 같은 이유로 큐 생성 지점도 분리한다.
     *
     * drop-oldest는 "poll로 만든 자리를 다른 로깅 스레드가 먼저 채간다"는 경쟁을 전제로 재시도하는데,
     * 그 경쟁은 스레드를 많이 띄운다고 결정적으로 재현되지 않는다. 테스트는 offer가 실패하는 큐를
     * 끼워 넣어 그 순간만 정확히 흉내 낸다.
     */
    BlockingQueue<MonitoringEventResponse> createQueue(int capacity) {
        return new ArrayBlockingQueue<>(capacity);
    }

    int activeSubscriptionCount() {
        return subscriptions.size();
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

        // ON/OFF가 바뀌었다는 표시. 역시 다음 송신 기회에 알린다.
        private final AtomicBoolean streamStateChanged = new AtomicBoolean();

        private volatile Thread sender;

        private Subscription(SseEmitter emitter) {
            this.emitter = emitter;
            this.queue = createQueue(Math.max(1, properties.getQueueCapacity()));
            this.heartbeatMillis = heartbeatIntervalMillis();
        }

        private void start() {
            sender = Thread.ofVirtual()
                    .name("monitoring-sse-" + subscriptionSequence.incrementAndGet())
                    .start(this::run);
        }

        /*
         * 이 구독의 큐가 가득 차면 이 관리자만 이벤트를 놓친다. 다른 연결은 영향이 없다.
         *
         * 가득 찼을 때 <b>가장 오래된 이벤트를 버리고 최신 이벤트를 넣는다</b>(drop-oldest).
         * 반대로 최신 것을 버리면, 밀릴수록 화면이 과거에 묶인다 — 장애가 커져 로그가 쏟아질수록
         * 관리자는 정작 "지금" 무슨 일이 나는지 못 보게 된다. 모니터링에서 가치 있는 건 오래된
         * backlog가 아니라 최신 상태다. 빠진 구간은 events-dropped 통지로 알린다.
         */
        private void offer(MonitoringEventResponse event) {
            if (queue.offer(event)) {
                return;
            }

            /*
             * 큐가 찼다. 자리를 만들고(poll) 곧바로 넣는다(offer).
             *
             * 이 순서가 핵심이다. offer를 먼저 하고 실패하면 poll하는 구조로 두면, 루프의 마지막
             * poll이 만든 자리에 아무도 넣지 않은 채 끝난다 — 오래된 이벤트를 버린 대가도 못 받고
             * 최신 이벤트까지 잃으면서 큐에는 빈자리가 남는다. poll 뒤에 항상 offer가 오게 두면
             * "비운 자리는 반드시 재시도한다"가 코드 모양으로 보장된다.
             *
             * 재시도가 필요한 이유는 poll로 만든 자리를 다른 로깅 스레드가 먼저 채울 수 있어서다.
             * 다만 무한 재시도는 로그를 남긴 요청 스레드를 붙잡으므로 횟수를 묶는다.
             */
            for (int attempt = 0; attempt < OFFER_ATTEMPTS; attempt++) {
                // poll()이 실제로 하나를 꺼냈을 때만 유실 1건이다. null이면 그 사이 송신
                // 스레드가 비운 것이라 버린 게 없다 — 여기서 세면 집계가 부풀어 오른다.
                if (queue.poll() != null) {
                    recordDropped();
                }

                if (queue.offer(event)) {
                    return;
                }
            }

            // 경쟁이 심해 끝내 자리를 못 잡았다. 이번 이벤트를 버린다.
            recordDropped();
        }

        private void recordDropped() {
            droppedEvents.increment();
            droppedSinceNotice.incrementAndGet();
        }

        private void onStreamStateChanged(boolean enabled) {
            if (!enabled) {
                // 전환 이전 이벤트가 다시 켰을 때 뒤늦게 나가지 않도록 버린다.
                queue.clear();
                // 유실 통지도 의미가 없어졌다 — 어차피 못 보낼 이벤트였다.
                droppedSinceNotice.set(0);
            }

            streamStateChanged.set(true);
        }

        /*
         * 송신은 전부 이 스레드에서만 한다. SseEmitter는 동시 송신에 안전하지 않아서 heartbeat를
         * 별도 스케줄러로 보내면 이벤트 송신과 겹칠 때 SSE 프레임이 깨진다. take() 대신
         * poll(timeout)을 쓰면 스레드를 더 늘리지 않고도 "이 연결이 유휴하면 heartbeat"가 된다.
         */
        private void run() {
            // 꺼져 있는 상태로 접속했으면 그 사실을 알려준다. 연결은 그대로 유지된다.
            if (!send(CONNECTED_EVENT, new ConnectedResponse(streamEnabled.get()))) {
                return;
            }

            while (active.get()) {
                try {
                    MonitoringEventResponse event = heartbeatMillis > 0
                            ? queue.poll(heartbeatMillis, TimeUnit.MILLISECONDS)
                            : queue.take();

                    if (!notifyStreamStateIfChanged()) {
                        return;
                    }

                    // 밀린 게 풀린 지금이 유실을 알릴 첫 기회다. 이벤트보다 먼저 보내야
                    // 화면에서 "여기서 N건이 빠졌다"는 순서가 맞는다.
                    if (!notifyDroppedIfAny()) {
                        return;
                    }

                    /*
                     * queue.clear()와 offer가 겹치면 전환 직후 이벤트가 큐에 남을 수 있다.
                     * 송신 직전에 한 번 더 확인해서 꺼진 상태로는 내보내지 않는다.
                     */
                    if (event != null && !streamEnabled.get()) {
                        continue;
                    }

                    // poll 타임아웃이면 null이다 — 이 연결이 heartbeat 간격만큼 유휴했다는 뜻.
                    // 꺼져 있어도 heartbeat는 계속 보내야 프록시가 연결을 끊지 않는다.
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

        private boolean notifyStreamStateIfChanged() {
            return !streamStateChanged.compareAndSet(true, false)
                    || send(CONNECTED_EVENT, new ConnectedResponse(streamEnabled.get()));
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
                /*
                 * 끊긴 연결이다. 구독만 정리하고 emitter는 건드리지 않는다.
                 *
                 * 예전에는 여기서 emitter.complete()를 불렀는데, 그게 이미 못 쓰는 응답을
                 * flush하면서 AsyncRequestNotUsableException을 만들고 → async dispatch로
                 * GlobalExceptionHandler까지 올라가 → 다시 JSON 본문을 쓰려다
                 * HttpMessageNotWritableException을 냈다(#191). 송신 실패는 컨테이너가 이미
                 * 알고 있는 사건이라 요청 정리도 컨테이너가 한다.
                 */
                detach();
                return false;
            }
        }

        /*
         * 구독 정리만 한다 — 목록에서 빼고, 큐를 비우고, 송신 스레드를 깨운다. emitter는 손대지 않는다.
         *
         * 컨테이너 콜백(onCompletion/onTimeout/onError)과 송신 실패가 모두 이 메서드로 들어오고,
         * 서로 겹쳐 들어올 수 있어 compareAndSet으로 첫 호출만 실제 정리를 수행한다.
         *
         * @return 이 호출이 실제로 정리를 수행했으면 true
         */
        private boolean detach() {
            if (!active.compareAndSet(true, false)) {
                return false;
            }

            subscriptions.remove(this);
            queue.clear();

            Thread currentSender = sender;
            if (currentSender != null && currentSender != Thread.currentThread()) {
                currentSender.interrupt();
            }

            return true;
        }

        /*
         * 정리에 더해 emitter까지 닫는다. 서버 종료처럼 애플리케이션이 먼저 끝내는 경우에만 쓴다.
         *
         * detach가 false를 돌려주면 이미 다른 경로로 끝난 구독이라는 뜻이므로 complete()를
         * 부르지 않는다. 이게 "송신 실패 후 complete 재호출"을 막는 지점이다.
         */
        private void complete() {
            if (detach()) {
                emitter.complete();
            }
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
