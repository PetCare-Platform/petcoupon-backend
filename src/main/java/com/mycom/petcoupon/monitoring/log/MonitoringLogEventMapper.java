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

    /*
     * 키와 값을 감쌀 수 있는 따옴표를 각각 그룹으로 잡는다.
     *
     * 없으면 `{"x-admin-key":"..."}` 같은 JSON 형태가 통째로 새어나간다 — 키워드 바로 뒤가
     * `"`라 \s*[:=] 조건이 깨져서 패턴이 부분 매칭도 아니고 아예 안 걸린다(실측 확인).
     * 헤더가 항상 Spring의 MultiValueMap toString 형태(`{X-ADMIN-KEY=...}`)로만 찍힌다는
     * 보장이 없다 — Jackson이 직렬화한 본문이나 구조화 로그는 키에 따옴표가 붙는다.
     *
     * 값에서 따옴표를 제외한 것도 같은 이유다. 포함하면 닫는 따옴표까지 삼켜서
     * `{"x-admin-key":"[REDACTED]}` 처럼 짝이 깨진 채로 화면에 나간다.
     *
     * cookie는 set-cookie도 함께 걸린다(앞의 `set-`은 매칭 밖이라 그대로 남는다).
     */
    private static final Pattern SCHEME_AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)(authorization)([\"']?)\\s*([:=])\\s*([\"']?)(?:bearer|basic)\\s+[^,;\\s\"'}\\]]+"
    );
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|authorization|cookie|api[-_]?key|access[-_]?key|"
                    + "x[-_]?admin[-_]?key)([\"']?)\\s*([:=])\\s*([\"']?)((?!\\[REDACTED\\])[^,;\\s\"'}\\]]+)"
    );

    /*
     * 키워드 없이 스킴만 붙어 있는 credential.
     *
     * 이 앱은 지금 Bearer/Basic을 쓰지 않는다(관리자 인증은 X-ADMIN-KEY) — 외부 연동이 붙을 때를 위한 선제 방어다.
     *
     * 위 두 패턴은 authorization 같은 키가 있어야 걸린다. 그런데 인증 필터가 헤더값만 떼어
     * `"인증 실패: " + header` 로 찍으면 `Bearer eyJ...` 만 남아 전부 통과한다(실측 확인).
     *
     * 반드시 위 두 패턴 *뒤에* 돌려야 한다. 먼저 돌리면 `Authorization: Bearer x` 가
     * `Authorization: Bearer [REDACTED]` 가 돼서 키를 남기고 값만 가리는 기존 형식이 깨진다.
     *
     * 길이 하한 8은 산문 오탐을 줄이려는 것이다(실제 Bearer/Basic 값은 base64라 훨씬 길다).
     * 애매하면 가리는 쪽으로 기운 판단이다 — 관리자 화면에서 로그 한 줄 덜 읽는 것보다
     * credential 한 개 새는 게 비싸다.
     */
    private static final Pattern BARE_SCHEME_CREDENTIAL = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._\\-+/=]{8,}"
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

        // 스킴을 먼저 지우지 않으면 범용 패턴이 Bearer/Basic만 값으로 보고 실제 credential을 남긴다.
        // $2·$4는 키/값을 감싼 따옴표다 — 되돌려놔야 JSON 형태의 짝이 유지된다.
        String sanitized = SCHEME_AUTHORIZATION_VALUE.matcher(scanned).replaceAll("$1$2$3$4[REDACTED]");
        sanitized = SECRET_VALUE.matcher(sanitized).replaceAll("$1$2$3$4[REDACTED]");
        // 키가 없어 위에서 못 걸린 스킴 단독 형태를 마지막으로 훑는다.
        sanitized = BARE_SCHEME_CREDENTIAL.matcher(sanitized).replaceAll("$1 [REDACTED]");
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
