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

        MonitoringLogAppender.bind(monitoringSseService);
        appender = new MonitoringLogAppender();
        appender.setName(MonitoringLogAppender.NAME);
        appender.setContext(loggerContext);
        appender.start();
        rootLogger.addAppender(appender);
    }

    @PreDestroy
    void detach() {
        MonitoringLogAppender.unbind(monitoringSseService);
        if (rootLogger != null && appender != null) {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
