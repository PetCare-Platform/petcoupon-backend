package com.mycom.petcoupon.monitoring.exception;

import org.springframework.http.HttpStatus;

import com.mycom.petcoupon.global.common.code.BaseErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MonitoringErrorCode implements BaseErrorCode {

	/*
	 * 503으로 응답하는 이유: 요청 자체는 정상이고 지금 서버가 더 받을 수 없을 뿐이라
	 * 잠시 뒤 재시도하면 성공한다. 4xx로 주면 프론트가 "내 요청이 잘못됐다"로 해석해
	 * 재시도를 포기할 수 있다.
	 */
	TOO_MANY_STREAM_CONNECTIONS(
			HttpStatus.SERVICE_UNAVAILABLE,
			"MONITORING503-0",
			"모니터링 스트림 동시 연결 수가 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."
	);

	private final HttpStatus status;
	private final String code;
	private final String message;
}
