package com.mycom.petcoupon.monitoring.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Root logger에 덧붙는 관측 전용 appender다. 여기서는 절대 SSE I/O를 하지 않고 bounded queue에
 * 넣는 요청만 남긴다.
 *
 * <p>sink는 생성자로 받는다. logback.xml이 아니라
 * {@code MonitoringLogAppenderConfiguration}이 직접 생성해 root logger에 붙이므로 전역 static
 * 상태를 둘 이유가 없다. static이면 Spring 컨텍스트가 둘 이상 뜨는 상황(테스트 컨텍스트 캐싱이
 * 정확히 그렇다)에서 나중 컨텍스트가 앞선 sink를 덮어써 서로 간섭한다.
 */
public class MonitoringLogAppender extends AppenderBase<ILoggingEvent> {

    public static final String NAME = "MONITORING";

    private final MonitoringLogEventSink sink;

    public MonitoringLogAppender(MonitoringLogEventSink sink) {
        this.sink = sink;
    }

    @Override
    protected void append(ILoggingEvent event) {
        Level level = event.getLevel();
        if (level != Level.WARN && level != Level.ERROR) {
            return;
        }

        // 스트림이 꺼져 있거나 보고 있는 관리자가 없으면 이벤트를 만들지도 않는다.
        // 관리 화면을 열어두지 않는 평상시에 WARN/ERROR 로깅이 공짜에 가까워야 한다.
        if (!sink.isAcceptingEvents()) {
            return;
        }

        sink.offer(MonitoringLogEventMapper.from(event));
    }
}
