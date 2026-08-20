package com.mycom.petcoupon.event.exception.code;

import org.springframework.http.HttpStatus;

import com.mycom.petcoupon.global.common.code.BaseErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EventErrorCode implements BaseErrorCode {

	INVALID_EVENT_PERIOD(HttpStatus.BAD_REQUEST, "EVENT400-0", "이벤트 기간이 올바르지 않습니다."),

	EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT404-0", "존재하지 않는 이벤트입니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
