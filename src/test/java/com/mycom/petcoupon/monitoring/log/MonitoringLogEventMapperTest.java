package com.mycom.petcoupon.monitoring.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 마스킹 규칙과 길이 상한 검증. 지금까지 MonitoringLogAppenderTest가 곁다리로 확인하던 걸
 * 분리하고, 그때 빠져 있던 분기(절단, 스캔 상한, 비-SQL 긴 메시지)를 채운다.
 */
class MonitoringLogEventMapperTest {

    @Test
    @DisplayName("빈 메시지는 안내 문구로 대체한다")
    void replacesBlankMessage() {
        assertThat(MonitoringLogEventMapper.sanitize(null)).isEqualTo("로그 메시지가 없습니다.");
        assertThat(MonitoringLogEventMapper.sanitize("   ")).isEqualTo("로그 메시지가 없습니다.");
    }

    @Test
    @DisplayName("비밀값은 키를 남기고 값만 가린다")
    void redactsSecretValues() {
        assertThat(MonitoringLogEventMapper.sanitize("password=very-secret"))
                .isEqualTo("password=[REDACTED]");
        assertThat(MonitoringLogEventMapper.sanitize("token: abc.def"))
                .isEqualTo("token:[REDACTED]");
        assertThat(MonitoringLogEventMapper.sanitize("api-key=k1, access_key=k2"))
                .isEqualTo("api-key=[REDACTED], access_key=[REDACTED]");
    }

    @Test
    @DisplayName("스킴이 붙은 Authorization credential 전체를 가린다")
    void redactsSchemePrefixedAuthorizationCredentials() {
        assertThat(MonitoringLogEventMapper.sanitize("Authorization: Bearer abc.def"))
                .isEqualTo("Authorization:[REDACTED]");
        assertThat(MonitoringLogEventMapper.sanitize("Authorization=Basic dXNlcjpwYXNz"))
                .isEqualTo("Authorization=[REDACTED]");
    }

    @Test
    @DisplayName("관리자 세션 헤더값을 가린다")
    void redactsAdminSessionHeader() {
        assertThat(MonitoringLogEventMapper.sanitize("X-ADMIN-KEY: session-token"))
                .isEqualTo("X-ADMIN-KEY:[REDACTED]");
    }

    @Test
    @DisplayName("인증정보 뒤의 일반 로그 내용은 보존한다")
    void preservesContextAfterRedactingCredentials() {
        assertThat(MonitoringLogEventMapper.sanitize(
                "headers={Authorization=Bearer abc.def, Accept=application/json} request failed"))
                .isEqualTo("headers={Authorization=[REDACTED], Accept=application/json} request failed");
        assertThat(MonitoringLogEventMapper.sanitize(
                "Authorization: Bearer abc.def request failed"))
                .isEqualTo("Authorization:[REDACTED] request failed");
        assertThat(MonitoringLogEventMapper.sanitize("headers={Authorization=Bearer abc.def}"))
                .isEqualTo("headers={Authorization=[REDACTED]}");
        assertThat(MonitoringLogEventMapper.sanitize("headers={X-ADMIN-KEY=session-token}"))
                .isEqualTo("headers={X-ADMIN-KEY=[REDACTED]}");
    }

    @Test
    @DisplayName("SQL과 인프라 연결 정보는 통째로 가린다")
    void blanksSqlAndInfrastructure() {
        assertThat(MonitoringLogEventMapper.sanitize("delete from coupon_issue where id = 3"))
                .isEqualTo("SQL 내용은 보안상 생략되었습니다.");
        assertThat(MonitoringLogEventMapper.sanitize("connect failed: jdbc:mysql://db:3306/petcoupon"))
                .isEqualTo("인프라 연결 정보는 보안상 생략되었습니다.");
        assertThat(MonitoringLogEventMapper.sanitize("redis://cache.internal:6379 응답 없음"))
                .isEqualTo("인프라 연결 정보는 보안상 생략되었습니다.");
    }

    @Test
    @DisplayName("연속된 줄바꿈과 탭은 공백 하나로 접는다")
    void collapsesControlWhitespace() {
        assertThat(MonitoringLogEventMapper.sanitize("앞\n\t뒤")).isEqualTo("앞 뒤");
    }

    @Test
    @DisplayName("긴 메시지는 500자로 자르고 말줄임표를 붙인다")
    void truncatesLongMessage() {
        // SQL/인프라 패턴이 없는 순수하게 긴 메시지 — 기존 테스트가 덮지 못하던 분기다.
        String message = "가".repeat(600);

        String sanitized = MonitoringLogEventMapper.sanitize(message);

        assertThat(sanitized).hasSize(501).endsWith("…");
        assertThat(sanitized).startsWith("가가가");
    }

    @Test
    @DisplayName("보여주는 구간에 SQL이 있으면 스캔 상한과 무관하게 잡는다")
    void detectsSqlInsideDisplayedRange() {
        // 표시 상한(500)은 스캔 상한(2048) 안에 있으므로, 화면에 나갈 내용은 항상 검사된다.
        String message = "x".repeat(400) + " select id from coupon " + "y".repeat(5000);

        assertThat(MonitoringLogEventMapper.sanitize(message))
                .isEqualTo("SQL 내용은 보안상 생략되었습니다.");
    }

    @Test
    @DisplayName("스캔 상한 덕분에 병적인 입력도 즉시 끝난다")
    void boundsCostForPathologicalInput() {
        // 'update'만 잔뜩 있고 'set'이 없어 매칭에 실패하는 입력. 상한이 없으면 .+? 가 매 위치마다
        // 문자열 끝까지 확장돼 100KB에서 4.7초가 걸렸다.
        String message = "update ".repeat(20_000);

        long startedAt = System.nanoTime();
        String sanitized = MonitoringLogEventMapper.sanitize(message);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        assertThat(sanitized).hasSize(501).endsWith("…");
    }
}
