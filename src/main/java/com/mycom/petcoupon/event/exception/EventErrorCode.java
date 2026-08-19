package com.mycom.petcoupon.event.exception;

import org.springframework.http.HttpStatus;

import com.mycom.petcoupon.global.common.code.BaseErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EventErrorCode implements BaseErrorCode {

	EVENT_NOT_FOUND(
			HttpStatus.NOT_FOUND,
			"EVENT404-0",
			"이벤트를 찾을 수 없습니다."
	),
	
	INVALID_EVENT_PERIOD(
			HttpStatus.BAD_REQUEST,
			"EVENT400-0",
			"이벤트 종료 시각은 오픈 시각보다 늦어야 합니다."
	),

	ADMIN_USER_NOT_FOUND(
			HttpStatus.INTERNAL_SERVER_ERROR,
			"EVENT500-0",
			"활성 관리자 계정을 찾을 수 없습니다."
	);

	private final HttpStatus status;
	private final String code;
	private final String message;
}
