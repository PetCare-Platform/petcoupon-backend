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

    // 관리자 화면에 실제로 보여주는 길이.
    private static final int MAX_MESSAGE_LENGTH = 500;

    /*
     * 마스킹 검사를 돌릴 최대 길이.
     *
     * SQL_STATEMENT의 .+? 는 매칭에 "실패"할 때 각 select/update 위치마다 문자열 끝까지
     * 확장되므로 비용이 입력 길이의 제곱으로 커진다. 측정값(update 반복 입력):
     * 10KB 45ms, 50KB 1.1s, 100KB 4.7s. 이 비용은 로그를 남긴 요청 스레드가 내고,
     * 하필 WARN/ERROR가 쏟아지는 장애 상황에 발동한다. 그래서 검사 대상을 잘라 상한을 둔다
     * (2KB 최악 약 3ms).
     *
     * MAX_MESSAGE_LENGTH보다 크게 잡는 게 핵심이다. 보여주는 구간이 검사한 구간에 반드시
     * 포함되어야(500 < 2048) "검사 안 한 내용을 노출"하는 일이 생기지 않는다.
     */
    private static final int MAX_SCAN_LENGTH = 2_048;

    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|authorization|api[-_]?key|access[-_]?key)\\s*([:=])\\s*([^,;\\s]+)"
    );

    /*
     * DOTALL(?s)을 유지하는 이유: 빼면 .+? 가 줄을 못 넘어서 여러 줄로 포맷된
     * SELECT 목록을 놓친다. 이 프로젝트는 네이티브 쿼리를 text block으로 쓰고 있어 그런
     * 문자열이 로그에 실릴 수 있다. 비용은 위 MAX_SCAN_LENGTH로 이미 묶여 있다.
     */
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?is)\\b(select\\s+.+?\\s+from|insert\\s+into|update\\s+.+?\\s+set|delete\\s+from)\\b"
    );
    private static final Pattern INFRASTRUCTURE_DETAILS = Pattern.compile(
            "(?i)(jdbc:|redis://|kafka://|spring\\.datasource|db_(url|username|password)|"
                    + "redis_(host|password)|kafka_(bootstrap_servers|sasl)|admin_auth_code)"
    );
    private static final Pattern CONTROL_WHITESPACE = Pattern.compile("[\\r\\n\\t]+");

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

        // 검사 구간을 먼저 자른다. 뒤쪽은 어차피 화면에 나가지 않으므로 검사할 필요도 없다.
        String scanned = message.length() <= MAX_SCAN_LENGTH
                ? message
                : message.substring(0, MAX_SCAN_LENGTH);

        if (SQL_STATEMENT.matcher(scanned).find()) {
            return "SQL 내용은 보안상 생략되었습니다.";
        }

        if (INFRASTRUCTURE_DETAILS.matcher(scanned).find()) {
            return "인프라 연결 정보는 보안상 생략되었습니다.";
        }

        String sanitized = SECRET_VALUE.matcher(scanned).replaceAll("$1$2[REDACTED]");
        sanitized = CONTROL_WHITESPACE.matcher(sanitized).replaceAll(" ").trim();

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
