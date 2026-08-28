package com.mycom.petcoupon.monitoring.log;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;

import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;

/**
 * 로그 객체를 프론트에 노출해도 되는 최소 데이터로 축소한다.
 */
public final class MonitoringLogEventMapper {

    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|authorization|api[-_]?key|access[-_]?key)\\s*([:=])\\s*([^,;\\s]+)"
    );
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?is)\\b(select\\s+.+?\\s+from|insert\\s+into|update\\s+.+?\\s+set|delete\\s+from)\\b"
    );
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[^\\s,;]+");
    private static final Pattern INFRASTRUCTURE_DETAILS = Pattern.compile(
            "(?i)(jdbc:|redis://|kafka://|spring\\.datasource|db_(url|username|password)|"
                    + "redis_(host|password)|kafka_(bootstrap_servers|sasl)|admin_auth_code)"
    );

    private MonitoringLogEventMapper() {
    }

    public static MonitoringEventResponse from(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();

        return new MonitoringEventResponse(
                UUID.randomUUID().toString(),
                event.getLevel().toString(),
                simpleName(event.getLoggerName()),
                sanitize(event.getFormattedMessage()),
                throwable == null ? null : simpleName(throwable.getClassName()),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault())
        );
    }

    static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "로그 메시지가 없습니다.";
        }

        if (SQL_STATEMENT.matcher(message).find()) {
            return "SQL 내용은 보안상 생략되었습니다.";
        }

        if (INFRASTRUCTURE_DETAILS.matcher(message).find()) {
            return "인프라 연결 정보는 보안상 생략되었습니다.";
        }

        String sanitized = SECRET_VALUE.matcher(message).replaceAll("$1$2[REDACTED]");
        sanitized = JDBC_URL.matcher(sanitized).replaceAll("jdbc:[REDACTED]");
        sanitized = sanitized.replaceAll("[\\r\\n\\t]+", " ").trim();

        return sanitized.length() <= MAX_MESSAGE_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    private static String simpleName(String className) {
        if (className == null || className.isBlank()) {
            return "unknown";
        }

        int separator = className.lastIndexOf('.');
        return separator >= 0 ? className.substring(separator + 1) : className;
    }
}
