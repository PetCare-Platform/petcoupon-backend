package com.mycom.petcoupon.monitoring.log;

import java.util.List;

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
    private final List<String> excludedLoggerPrefixes;

    public MonitoringLogAppender(MonitoringLogEventSink sink, List<String> excludedLoggerPrefixes) {
        this.sink = sink;
        this.excludedLoggerPrefixes = excludedLoggerPrefixes == null
                ? List.of()
                : List.copyOf(excludedLoggerPrefixes);
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

        if (isExcluded(event.getLoggerName())) {
            return;
        }

        sink.offer(MonitoringLogEventMapper.from(event));
    }

    /*
     * root logger에 붙는 이상 프레임워크 내부 로그도 전부 지나간다. 여기서 거르는 건 "관리자가 볼
     * 이유가 없는 것"뿐이고, 라이브러리라는 이유로 거르지는 않는다 — DB/Redis/Kafka 장애는 대부분
     * 라이브러리 로거에서 나오기 때문이다(MonitoringProperties#excludedLoggers 참고).
     */
    private boolean isExcluded(String loggerName) {
        if (loggerName == null || excludedLoggerPrefixes.isEmpty()) {
            return false;
        }

        for (String prefix : excludedLoggerPrefixes) {
            if (loggerName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }
}
