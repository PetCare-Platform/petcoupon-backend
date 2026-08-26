package com.mycom.petcoupon.global.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.mycom.petcoupon.global.auth.AdminAuthProperties;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

/**
 * ADMIN_AUTH_CODE 환경변수 없이 뜬 상태(= application.properties의 기본값이 적용된 상태).
 * 로컬에서 별도 설정 없이 관리자 API를 쓸 수 있어야 한다.
 *
 * 이 테스트는 기본값을 제거하는 날 같이 지운다 —
 * AdminAuthProperties.LOCAL_DEV_CODE의 TODO 참고.
 *
 * 실행 전 Redis가 떠 있어야 한다: docker compose up -d redis
 */
@SpringBootTest
@TestPropertySource(properties = {
		AdminAuthTestProperties.STREAM_OFF,
		AdminAuthTestProperties.OUTBOX_OFF,
		AdminAuthTestProperties.KAFKA_LISTENER_OFF,
		AdminAuthTestProperties.EVENT_SCHEDULER_OFF,
		AdminAuthTestProperties.COUPON_SCHEDULER_OFF
})
class AdminSessionDefaultCodeTest {

	@Autowired
	private AdminSessionService adminSessionService;

	@Autowired
	private AdminAuthProperties properties;

	@Test
	void defaultCodeIsAppliedWhenEnvironmentVariableIsAbsent() {
		assertThat(properties.getCode()).isEqualTo(AdminAuthProperties.LOCAL_DEV_CODE);
		assertThat(properties.isUsingLocalDevCode()).isTrue();
	}

	@Test
	void issueSucceedsWithDefaultCode() {
		String token = adminSessionService.issue(AdminAuthProperties.LOCAL_DEV_CODE).token();

		assertThat(adminSessionService.isValid(token)).isTrue();

		adminSessionService.revoke(token);
	}

	// 기본값을 쓴다고 아무 값이나 통과하는 건 아니다.
	@Test
	void issueStillRejectsWrongCode() {
		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> adminSessionService.issue("wrong-code")
		);

		assertSame(CommonErrorCode.UNAUTHORIZED, exception.getErrorCode());
	}
}
