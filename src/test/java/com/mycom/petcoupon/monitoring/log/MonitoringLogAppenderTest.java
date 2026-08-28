package com.mycom.petcoupon.monitoring.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;

class MonitoringLogAppenderTest {

    private final RecordingSink sink = new RecordingSink();
    private final List<MonitoringEventResponse> received = sink.received;
    private MonitoringLogAppender appender;

    @BeforeEach
    void setUp() {
        appender = new MonitoringLogAppender(sink);
        appender.start();
    }

    @AfterEach
    void tearDown() {
        appender.stop();
    }

    @Test
    @DisplayName("구독자가 없으면 이벤트를 만들지도 않는다")
    void skipsMappingWhenSinkIsNotAccepting() {
        sink.accepting = false;

        appender.doAppend(event(Level.ERROR, "password=very-secret"));

        assertThat(received).isEmpty();
    }

    private static final class RecordingSink implements MonitoringLogEventSink {

        private final List<MonitoringEventResponse> received = new ArrayList<>();
        private volatile boolean accepting = true;

        @Override
        public boolean isAcceptingEvents() {
            return accepting;
        }

        @Override
        public void offer(MonitoringEventResponse event) {
            received.add(event);
        }
    }

    @Test
    void forwardsOnlyWarnAndErrorAsSanitizedEvents() {
        appender.doAppend(event(Level.INFO, "normal operation"));
        appender.doAppend(event(Level.WARN, "password=very-secret"));
        appender.doAppend(event(Level.ERROR, "select * from users where token = 'secret'"));

        assertThat(received).hasSize(2);
        assertThat(received.get(0))
                .extracting(MonitoringEventResponse::level, MonitoringEventResponse::source,
                        MonitoringEventResponse::message, MonitoringEventResponse::exception)
                .containsExactly("WARN", "CouponStatusScheduler", "password=[REDACTED]", null);
        assertThat(received.get(1).message()).isEqualTo("SQL 내용은 보안상 생략되었습니다.");
    }

    @Test
    void omitsInfrastructureConnectionInformation() {
        String message = "connection jdbc:mysql://db.internal:3306/petcoupon?password=very-secret "
                + "x".repeat(600);

        assertThat(MonitoringLogEventMapper.sanitize(message))
                .isEqualTo("인프라 연결 정보는 보안상 생략되었습니다.");
    }

    private LoggingEvent event(Level level, String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName("com.mycom.petcoupon.coupon.config.CouponStatusScheduler");
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }
}
