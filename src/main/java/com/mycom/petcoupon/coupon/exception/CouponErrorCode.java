package com.mycom.petcoupon.coupon.exception;

import org.springframework.http.HttpStatus;

import com.mycom.petcoupon.global.common.code.BaseErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 쿠폰 도메인 전용 에러코드.
 * CouponIssueService에서 GeneralException(errorCode)로 던지면
 * GlobalExceptionHandler가 잡아서 여기 정의된 status/code/message로 응답을 만들어준다.
 */
@Getter
@AllArgsConstructor
public enum CouponErrorCode implements BaseErrorCode {
    SOLD_OUT(HttpStatus.CONFLICT, "COUPON409-0", "쿠폰 재고가 모두 소진되었습니다."),
    DUPLICATE_USER(HttpStatus.CONFLICT, "COUPON409-1", "이미 발급받은 쿠폰입니다."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "COUPON409-2", "이미 처리된 요청입니다."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON404-0", "존재하지 않는 쿠폰입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}