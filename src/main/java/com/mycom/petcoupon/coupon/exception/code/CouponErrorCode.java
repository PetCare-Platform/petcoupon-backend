package com.mycom.petcoupon.coupon.exception.code;

import org.springframework.http.HttpStatus;

import com.mycom.petcoupon.global.common.code.BaseErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CouponErrorCode implements BaseErrorCode {

	INVALID_EVENT_STATUS(
			HttpStatus.BAD_REQUEST,
			"COUPON400-0",
			"쿠폰을 생성할 수 없는 이벤트 상태입니다."
	),

	INVALID_ISSUE_PERIOD(
			HttpStatus.BAD_REQUEST,
			"COUPON400-1",
			"쿠폰 발급 종료 시각은 시작 시각보다 이후여야 합니다."
	),

	ISSUE_PERIOD_OUT_OF_EVENT_PERIOD(
			HttpStatus.BAD_REQUEST,
			"COUPON400-2",
			"쿠폰 발급 기간은 이벤트 기간 내에 있어야 합니다."
	),

	INVALID_RATE_DISCOUNT_POLICY(
			HttpStatus.BAD_REQUEST,
			"COUPON400-3",
			"정률 할인 정책이 올바르지 않습니다."
	),

	INVALID_FIXED_AMOUNT_DISCOUNT_POLICY(
			HttpStatus.BAD_REQUEST,
			"COUPON400-4",
			"정액 할인에는 최대 할인 금액을 설정할 수 없습니다."
	);

	private final HttpStatus status;
	private final String code;
	private final String message;
}
