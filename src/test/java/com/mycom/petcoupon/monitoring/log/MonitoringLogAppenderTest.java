package com.mycom.petcoupon.monitoring.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.monitoring.config.MonitoringProperties;
import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;

class MonitoringLogAppenderTest {

    private final RecordingSink sink = new RecordingSink();
    private final List<MonitoringEventResponse> received = sink.received;
    private MonitoringLogAppender appender;

    @BeforeEach
    void setUp() {
        appender = new MonitoringLogAppender(sink, List.of("org.springframework.context.support"));
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

    @Test
    @DisplayName("제외 목록에 걸린 로거는 올리지 않는다")
    void skipsExcludedLoggers() {
        // 기동 시점에만 나오고 관리자에겐 의미가 없는 잡음. 전체 스위트 WARN 1위였다.
        appender.doAppend(event(Level.WARN, "not eligible for auto-proxying",
                "org.springframework.context.support.PostProcessorRegistrationDelegate$BeanPostProcessorChecker"));

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("기본 제외 목록이 SSE 오류 로그의 재유입을 막는다")
    void blocksExceptionHandlerResolverFeedbackLoop() {
        // 운영에서 실제로 쓰이는 기본값으로 검증한다. 여기서만 통과하고 기본값에 빠져 있으면 의미가 없다.
        MonitoringLogAppender defaults =
                new MonitoringLogAppender(sink, new MonitoringProperties().getExcludedLoggers());
        defaults.start();

        /*
         * SSE 응답이 끊긴 뒤 GlobalExceptionHandler가 JSON 오류 본문을 쓰려다 실패하면 이 WARN이 난다.
         * 이걸 수집하면 SSE 오류 → 모니터링 이벤트 → 송신 실패 → 다시 SSE 오류로 순환한다(#191).
         */
        defaults.doAppend(event(Level.WARN, "Failure in @ExceptionHandler ...",
                "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver"));

        assertThat(received).isEmpty();

        // 반면 실제 애플리케이션 500 오류는 GlobalExceptionHandler가 직접 남기므로 계속 올라와야 한다.
        defaults.doAppend(event(Level.ERROR, "[Unhandled Exception]",
                "com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler"));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).source()).isEqualTo("GlobalExceptionHandler");

        defaults.stop();
    }

    @Test
    @DisplayName("모니터링 자신의 로그는 수집하지 않는다")
    void blocksSelfCollectionFromMonitoringPackage() {
        MonitoringLogAppender defaults =
                new MonitoringLogAppender(sink, new MonitoringProperties().getExcludedLoggers());
        defaults.start();

        /*
         * 스트림이 아파서 남긴 로그가 다시 그 스트림으로 들어가면 장애일수록 이벤트가 불어난다.
         * 관측 도구는 자기 자신을 관측 대상에 넣지 않는다(#191).
         */
        defaults.doAppend(event(Level.ERROR, "SSE 송신 실패",
                "com.mycom.petcoupon.monitoring.service.MonitoringSseService"));
        defaults.doAppend(event(Level.WARN, "appender 오류",
                "com.mycom.petcoupon.monitoring.log.MonitoringLogAppender"));

        assertThat(received).isEmpty();

        // 같은 최상위 패키지라도 모니터링 밖이면 그대로 수집된다.
        defaults.doAppend(event(Level.ERROR, "발급 실패",
                "com.mycom.petcoupon.coupon.service.CouponIssueService"));

        assertThat(received).hasSize(1);

        defaults.stop();
    }

    @Test
    @DisplayName("제외 목록에 없으면 라이브러리 로거라도 올린다")
    void keepsInfrastructureLoggers() {
        // DB/Redis 장애는 대부분 라이브러리 로거에서 나온다 — 패키지가 남이라고 버리면 안 된다.
        appender.doAppend(event(Level.ERROR, "connection pool exhausted", "org.hibernate.orm.jdbc.error"));
        appender.doAppend(event(Level.WARN, "reconnect failed", "io.lettuce.core.protocol.ConnectionWatchdog"));

        assertThat(received).hasSize(2);
        assertThat(received).extracting(MonitoringEventResponse::source)
                .containsExactly("error", "ConnectionWatchdog");
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
        return event(level, message, "com.mycom.petcoupon.coupon.config.CouponStatusScheduler");
    }

    private LoggingEvent event(Level level, String message, String loggerName) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName(loggerName);
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }
}
