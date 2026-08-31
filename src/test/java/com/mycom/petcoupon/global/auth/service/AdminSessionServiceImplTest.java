package com.mycom.petcoupon.global.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import com.mycom.petcoupon.global.auth.AdminAuthProperties;
import com.mycom.petcoupon.global.auth.dto.res.AdminSessionCreateResponse;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

/**
 * 세션 저장이 Redis TTL에 의존하므로 실제 Redis에 붙여 검증한다.
 * Mock으로는 "해시를 키로 쓴다"거나 "TTL이 걸린다"를 확인할 수 없다.
 *
 * 배경 작업을 전부 꺼둔다(AdminAuthTestProperties 참고). 앱을 통째로 띄우는 테스트라
 * 그대로 두면 스케줄러와 Kafka 컨슈머가 같은 DB·브로커를 쓰는 다른 테스트에 끼어든다.
 *
 * 실행 전 Redis가 떠 있어야 한다: docker compose up -d redis
 */
@SpringBootTest
@TestPropertySource(properties = {
		"admin.auth.code=test-admin-auth-code",
		"admin.auth.session-ttl=PT10M",
		AdminAuthTestProperties.STREAM_OFF,
		AdminAuthTestProperties.OUTBOX_OFF,
		AdminAuthTestProperties.KAFKA_LISTENER_OFF,
		AdminAuthTestProperties.EVENT_SCHEDULER_OFF,
		AdminAuthTestProperties.COUPON_SCHEDULER_OFF
})
class AdminSessionServiceImplTest {

	private static final String VALID_CODE = "test-admin-auth-code";

	@Autowired
	private AdminSessionService adminSessionService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private AdminAuthProperties properties;

	@BeforeEach
	void clearSessions() {
		redisTemplate.delete(redisTemplate.keys("admin:session:*"));
	}

	@Test
	void issueReturnsTokenAndExpiryWhenAuthCodeMatches() {
		AdminSessionCreateResponse response = adminSessionService.issue(VALID_CODE);

		assertThat(response.token()).isNotBlank();
		assertThat(response.expiresAt()).isNotNull();
		assertThat(adminSessionService.isValid(response.token())).isTrue();
	}

	@Test
	void issueThrowsUnauthorizedWhenAuthCodeDoesNotMatch() {
		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> adminSessionService.issue("wrong-code")
		);

		assertSame(CommonErrorCode.UNAUTHORIZED, exception.getErrorCode());
	}

	// 발급할 때마다 다른 토큰이 나와야 한 세션을 폐기해도 다른 세션이 살아 있다.
	@Test
	void issueGeneratesDistinctTokens() {
		String first = adminSessionService.issue(VALID_CODE).token();
		String second = adminSessionService.issue(VALID_CODE).token();

		assertThat(first).isNotEqualTo(second);
		assertThat(adminSessionService.isValid(first)).isTrue();
		assertThat(adminSessionService.isValid(second)).isTrue();
	}

	// 토큰 평문이 Redis에 남으면 저장소가 읽히는 순간 그대로 관리자 권한이 된다.
	@Test
	void issueStoresHashedTokenNotRawToken() {
		String token = adminSessionService.issue(VALID_CODE).token();

		assertThat(redisTemplate.keys("admin:session:*"))
				.isNotEmpty()
				.noneMatch(key -> key.contains(token));
	}

	@Test
	void issueAppliesConfiguredTtl() {
		String token = adminSessionService.issue(VALID_CODE).token();
		String key = redisTemplate.keys("admin:session:*").iterator().next();

		Duration ttl = Duration.ofSeconds(redisTemplate.getExpire(key));

		assertThat(adminSessionService.isValid(token)).isTrue();
		assertThat(ttl)
				.isPositive()
				.isLessThanOrEqualTo(properties.getSessionTtl());
	}

	@Test
	void isValidReturnsFalseForUnknownOrEmptyToken() {
		assertThat(adminSessionService.isValid("never-issued")).isFalse();
		assertThat(adminSessionService.isValid("")).isFalse();
		assertThat(adminSessionService.isValid(null)).isFalse();
	}

	@Test
	void revokeInvalidatesToken() {
		String token = adminSessionService.issue(VALID_CODE).token();

		adminSessionService.revoke(token);

		assertThat(adminSessionService.isValid(token)).isFalse();
	}

	// 폐기는 자기 세션만 끊어야 한다.
	@Test
	void revokeDoesNotAffectOtherSessions() {
		String revoked = adminSessionService.issue(VALID_CODE).token();
		String kept = adminSessionService.issue(VALID_CODE).token();

		adminSessionService.revoke(revoked);

		assertThat(adminSessionService.isValid(revoked)).isFalse();
		assertThat(adminSessionService.isValid(kept)).isTrue();
	}
}
