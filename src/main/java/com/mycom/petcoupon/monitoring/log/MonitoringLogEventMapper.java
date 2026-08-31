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
     */
    /*
     * 스킴 이름과 값 사이를 열어둔 이유(리뷰 지적 1·3 — 뿌리가 같다).
     *
     * 예전엔 authorization 바로 뒤에 bearer|basic이 와야만 걸렸다. 그래서 두 경우가 샜다.
     *  - 값이 []로 감싸인 형태: HttpHeaders/MultiValueMap의 toString은 `Authorization=[Bearer x]`다.
     *    '['가 끼어 이 패턴이 빗나가고, 범용 패턴은 '[Bearer'만 값으로 잡아 실제 토큰을 남겼다.
     *  - Bearer/Basic이 아닌 스킴: `Authorization: Token ghp_xxx`처럼 Token/Digest/SSWS 등은
     *    스킴 이름만 가려지고 credential이 그대로 남았다.
     * 둘 다 출력이 `[REDACTED] <진짜값>` 모양이었던 게 같은 원인이라는 증거다(실측 확인).
     *
     * 그래서 스킴을 특정 목록 대신 토큰 형태로 받고, 값 앞의 '['는 소비만 한다(따옴표와 달리
     * 되돌려놓지 않는다 — '[REDACTED]'가 이미 대괄호를 갖고 있어 '[[REDACTED]]'가 되기 때문이고,
     * 키워드만 있는 `X-ADMIN-KEY=[...]` 경로의 기존 출력과도 이쪽이 일치한다).
     *
     * 한계: Digest처럼 값이 쉼표로 이어지는 스킴은 첫 조각까지만 가린다. 쉼표는 헤더 구분자와
     * 겹쳐서 더 먹으면 다음 헤더를 삼킨다. 이 앱은 Authorization 헤더를 읽지 않으므로 그대로 둔다.
     */
    private static final Pattern SCHEME_AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)(authorization)([\"']?)\\s*([:=])\\s*([\"']?)\\[?"
                    + "[A-Za-z][A-Za-z0-9-]*\\s+[^,;\\s\"'}\\]]+"
    );
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|authorization|api[-_]?key|access[-_]?key|"
                    + "x[-_]?admin[-_]?key)([\"']?)\\s*([:=])\\s*([\"']?)((?!\\[REDACTED\\])[^,;\\s\"'}\\]]+)"
    );

    /*
     * 쿠키는 위 일반 규칙으로 처리하면 안 된다.
     *
     * 한 헤더 안에 name=value 쌍이 ';'로 여러 개 붙는데, 일반 값 클래스는 ';'에서 끊긴다.
     * 그러면 첫 쌍만 가려지고 뒤따르는 세션 쿠키가 그대로 남는다 — 세션이 첫 번째로 온다는
     * 보장이 없다(실측 확인):
     *   Cookie: theme=dark; JSESSIONID=abc  ->  Cookie:[REDACTED]; JSESSIONID=abc
     *
     * 그래서 쿠키만 따로, 쌍이 이어지는 동안 계속 가린다 — "한 조각 + (';' + 조각)*" 형태다.
     * 값 전체를 통째로(`[^,}\]]+`) 삼키는 방식도 되지만 그건 과하다. 실측해보니
     * `쿠키 파싱 실패 cookie=broken-value 이후 처리 중단`의 뒷문장까지 지워졌다 — 운영자가 봐야
     * 할 진단 정보다. 실제 쿠키 헤더는 ';' 뒤를 빼면 값에 공백이 없으므로, 그냥 붙은 공백에서
     * 멈추면 쿠키는 끝까지 덮으면서 뒤따르는 로그 문장은 살릴 수 있다.
     * Set-Cookie의 Path/HttpOnly 같은 속성도 ';'로 이어져 함께 가려진다.
     *
     * SECRET_VALUE보다 *먼저* 돌려야 한다. 나중에 돌리면 쿠키 값 안의 `token=...` 같은 조각이
     * 먼저 잡혀서 [REDACTED]가 박히고, 그 뒤 세션 쿠키는 그대로 남는다.
     *
     * set-cookie도 함께 걸린다(앞의 `set-`은 매칭 밖이라 그대로 남는다).
     */
    private static final Pattern COOKIE_VALUE = Pattern.compile(
            "(?i)(cookie)([\"']?)\\s*([:=])\\s*(?!\\[REDACTED\\])"
                    + "[^,;\\s\"'}\\]]+(?:\\s*;\\s*[^,;\\s\"'}\\]]+)*"
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
     * 길이 하한 8만으로는 부족했다(리뷰 지적 4). "Basic validation failed for request ..."의
     * validation이 8자를 넘어 가려졌다 — 운영자가 봐야 할 진단 문장이 조용히 사라진다(실측 확인).
     *
     * 그래서 "전부 소문자 알파벳인 낱말"을 뺀다. 영어 산문은 대개 여기 걸리고, 실제 credential은
     * base64·JWT·hex라 대문자나 숫자나 ._-+/= 중 하나는 반드시 섞여 있어 그대로 통과한다.
     * 앞에 ':'·'=' 같은 경계를 요구하는 방법도 있었지만, 그러면 `인증 실패 Bearer eyJ...`처럼
     * 구분자 없이 이어붙인 로그를 놓친다 — 이 패턴이 존재하는 이유가 바로 그 경우다.
     */
    private static final Pattern BARE_SCHEME_CREDENTIAL = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+(?![a-z]+\\b)[A-Za-z0-9._\\-+/=]{8,}"
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

        // 쿠키를 제일 먼저 통째로 지운다. 뒤로 미루면 쿠키 값 안의 조각이 범용 패턴에 먼저
        // 잡혀서, 정작 뒤쪽 세션 쿠키가 남는다.
        String sanitized = COOKIE_VALUE.matcher(scanned).replaceAll("$1$2$3[REDACTED]");

        // 스킴을 먼저 지우지 않으면 범용 패턴이 Bearer/Basic만 값으로 보고 실제 credential을 남긴다.
        // $2·$4는 키/값을 감싼 따옴표다 — 되돌려놔야 JSON 형태의 짝이 유지된다.
        sanitized = SCHEME_AUTHORIZATION_VALUE.matcher(sanitized).replaceAll("$1$2$3$4[REDACTED]");
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
