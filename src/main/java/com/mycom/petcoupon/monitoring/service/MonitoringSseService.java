package com.mycom.petcoupon.monitoring.service;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mycom.petcoupon.monitoring.config.MonitoringProperties;
import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;
import com.mycom.petcoupon.monitoring.log.MonitoringLogEventSink;

import lombok.RequiredArgsConstructor;

/**
 * 로그 발생 스레드와 SSE 송신을 분리한다. 큐가 가득 차면 이벤트만 버리고 핵심 요청은 기다리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MonitoringSseService implements MonitoringLogEventSink {

    private static final String CONNECTED_EVENT = "connected";
    private static final String MONITORING_EVENT = "monitoring-event";

    private final MonitoringProperties properties;
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean streamEnabled = new AtomicBoolean(true);
    private final AtomicLong deliveryGeneration = new AtomicLong();

    private volatile BlockingQueue<QueuedMonitoringEvent> queue;
    private volatile boolean running;
    private volatile Thread worker;

    @Override
    public void offer(MonitoringEventResponse event) {
        if (!streamEnabled.get()) {
            return;
        }

        // ArrayBlockingQueue.offer는 즉시 실패하므로 로그를 남긴 요청 스레드를 블로킹하지 않는다.
        queue().offer(new QueuedMonitoringEvent(event, deliveryGeneration.get()));
    }

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeout().toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ignored -> emitters.remove(emitter));

        if (!streamEnabled.get()) {
            sendConnectedAndClose(emitter, false);
            return emitter;
        }

        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event()
                    .name(CONNECTED_EVENT)
                    .data(new ConnectedResponse(true), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException exception) {
            emitters.remove(emitter);
            emitter.complete();
        }

        return emitter;
    }

    public boolean isStreamEnabled() {
        return streamEnabled.get();
    }

    public synchronized void setStreamEnabled(boolean enabled) {
        if (streamEnabled.getAndSet(enabled) == enabled) {
            return;
        }

        // 설정 전환 전 큐에 남은 이벤트는 새 상태에서 보내지 않는다.
        deliveryGeneration.incrementAndGet();
        queue().clear();

        if (!enabled) {
            emitters.forEach(SseEmitter::complete);
            emitters.clear();
        }
    }

    @jakarta.annotation.PostConstruct
    void startWorker() {
        running = true;
        worker = Thread.ofVirtual().name("monitoring-sse-worker").start(this::runWorker);
    }

    @jakarta.annotation.PreDestroy
    void stopWorker() {
        running = false;
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
        queue().clear();
    }

    private void runWorker() {
        while (running) {
            try {
                QueuedMonitoringEvent queued = queue().take();
                if (streamEnabled.get() && queued.generation() == deliveryGeneration.get()) {
                    broadcast(queued.event());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void broadcast(MonitoringEventResponse event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(MONITORING_EVENT)
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException exception) {
                emitters.remove(emitter);
                emitter.complete();
            }
        }
    }

    private void sendConnectedAndClose(SseEmitter emitter, boolean enabled) {
        try {
            emitter.send(SseEmitter.event()
                    .name(CONNECTED_EVENT)
                    .data(new ConnectedResponse(enabled), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
            // 연결 자체가 이미 끊긴 경우에도 monitoring 기능이 요청 처리에 영향을 주지 않는다.
        } finally {
            emitter.complete();
        }
    }

    private BlockingQueue<QueuedMonitoringEvent> queue() {
        BlockingQueue<QueuedMonitoringEvent> current = queue;
        if (current == null) {
            synchronized (this) {
                if (queue == null) {
                    queue = new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity()));
                }
                current = queue;
            }
        }
        return current;
    }

    private record QueuedMonitoringEvent(MonitoringEventResponse event, long generation) {
    }

    private record ConnectedResponse(boolean streamEnabled) {
    }
}
