package com.mycom.petcoupon.monitoring.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import com.mycom.petcoupon.monitoring.log.MonitoringLogAppender;
import com.mycom.petcoupon.monitoring.service.MonitoringSseService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/**
 * ConsoleAppender를 변경하지 않고 Root Logger에 monitoring appender만 추가한다.
 */
@Configuration
@RequiredArgsConstructor
public class MonitoringLogAppenderConfiguration {

    private final MonitoringSseService monitoringSseService;

    private MonitoringLogAppender appender;
    private Logger rootLogger;

    @PostConstruct
    void attach() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        appender = new MonitoringLogAppender(monitoringSseService);
        appender.setName(MonitoringLogAppender.NAME);
        appender.setContext(loggerContext);
        appender.start();
        rootLogger.addAppender(appender);
    }

    /*
     * LoggerContext는 JVM 전역이라 컨텍스트가 닫힐 때 반드시 떼어내야 한다. 안 그러면 테스트처럼
     * 컨텍스트가 여러 번 뜨고 닫히는 환경에서 죽은 appender가 root logger에 계속 쌓인다.
     */
    @PreDestroy
    void detach() {
        if (rootLogger != null && appender != null) {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
