package com.mycom.petcoupon.monitoring.log;

import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Root logger에 덧붙는 관측 전용 appender다. 여기서는 절대 SSE I/O를 하지 않고 bounded queue에
 * 넣는 요청만 남긴다. sink가 준비되기 전 또는 종료 후의 로그는 기존 appender만 처리한다.
 */
public class MonitoringLogAppender extends AppenderBase<ILoggingEvent> {

    public static final String NAME = "MONITORING";

    private static final AtomicReference<MonitoringLogEventSink> SINK = new AtomicReference<>();

    public static void bind(MonitoringLogEventSink sink) {
        SINK.set(sink);
    }

    public static void unbind(MonitoringLogEventSink sink) {
        SINK.compareAndSet(sink, null);
    }

    @Override
    protected void append(ILoggingEvent event) {
        Level level = event.getLevel();
        if (level != Level.WARN && level != Level.ERROR) {
            return;
        }

        MonitoringLogEventSink sink = SINK.get();
        if (sink != null) {
            sink.offer(MonitoringLogEventMapper.from(event));
        }
    }
}
