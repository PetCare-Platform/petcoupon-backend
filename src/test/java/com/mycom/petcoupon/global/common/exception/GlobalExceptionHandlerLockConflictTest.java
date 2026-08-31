package com.mycom.petcoupon.global.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.mycom.petcoupon.global.common.CustomResponse;

/**
 * 행 락 경합이 500이 아니라 409로 나가는지 지킨다.
 *
 * <p>전용 핸들러가 없던 시절엔 catch-all이 잡아 COMMON500-0으로 나갔다 — 관리자에게는 다시
 * 누르면 되는 상황이 "서버 내부 오류"로 보였고, ERROR 로그가 모니터링 화면까지 올라갔다.
 * 로그 레벨 검증은 {@link GlobalExceptionHandlerLogLevelTest}에 있다.
 */
class GlobalExceptionHandlerLockConflictTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	@DisplayName("락 획득 실패는 409와 COMMON409-0으로 응답한다")
	void lockAcquisitionFailure_returnsConflict() {
		ResponseEntity<CustomResponse<Void>> response =
				handler.handleLockConflict(new CannotAcquireLockException("could not obtain lock"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().isSuccess()).isFalse();
		assertThat(response.getBody().getCode()).isEqualTo("COMMON409-0");
		// 관리자가 무엇을 해야 하는지가 응답에 있어야 한다. 이게 없어서 지적받은 자리다.
		assertThat(response.getBody().getMessage()).contains("다시 시도");
	}

	@Test
	@DisplayName("락 대기 만료도 같은 409로 응답한다")
	void lockWaitTimeout_returnsConflict() {
		// MySQL 1205는 드라이버·설정에 따라 QueryTimeoutException으로 번역되는 경로가 있다.
		// 관리자 입장에서 둘은 같은 사건이므로 응답도 같아야 한다.
		assertThat(handler.handleLockConflict(new QueryTimeoutException("Lock wait timeout exceeded"))
				.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		assertThat(handler.handleLockConflict(new PessimisticLockingFailureException("deadlock found"))
				.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("오류 응답 Content-Type은 Accept와 무관하게 JSON이다")
	void alwaysRespondsWithJson() {
		// jsonError를 거치는지 확인한다. 안 거치면 Accept 협상 실패로 500이 되는 경로가 있다.
		assertThat(handler.handleLockConflict(new CannotAcquireLockException("could not obtain lock"))
				.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
	}
}
