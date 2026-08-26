package com.mycom.petcoupon.global.auth.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

/**
 * ADMIN_AUTH_CODE를 설정하지 않고 서버가 떴을 때의 동작.
 *
 * 이 경우 애플리케이션은 정상 기동하되 세션을 아무도 받지 못해야 한다. 기동을 막으면
 * 설정이 없는 로컬·테스트 환경이 전부 죽고, 반대로 인증을 통과시키면 관리자 API가
 * 무방비로 열린다. 둘 다 피하려면 "뜨긴 뜨되 닫혀 있는" 상태가 되어야 한다.
 *
 * 실행 전 Redis가 떠 있어야 한다: docker compose up -d redis
 */
@SpringBootTest
@TestPropertySource(properties = {
		"admin.auth.code=",
		AdminAuthTestProperties.STREAM_OFF,
		AdminAuthTestProperties.OUTBOX_OFF,
		AdminAuthTestProperties.KAFKA_LISTENER_OFF,
		AdminAuthTestProperties.EVENT_SCHEDULER_OFF,
		AdminAuthTestProperties.COUPON_SCHEDULER_OFF
})
class AdminSessionAuthCodeMissingTest {

	@Autowired
	private AdminSessionService adminSessionService;

	@Test
	void issueIsRejectedWhenAuthCodeIsNotConfigured() {
		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> adminSessionService.issue("anything")
		);

		assertSame(CommonErrorCode.UNAUTHORIZED, exception.getErrorCode());
	}

	// 빈 문자열을 보내면 설정값(빈 문자열)과 "같으니" 통과하는 실수를 막는다.
	@Test
	void issueIsRejectedEvenWhenProvidedCodeIsAlsoBlank() {
		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> adminSessionService.issue("")
		);

		assertSame(CommonErrorCode.UNAUTHORIZED, exception.getErrorCode());
	}
}
