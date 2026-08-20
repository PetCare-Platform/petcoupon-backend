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
    // 아래 2개는 이슈 #16(Idempotency-Key) 추가분 — IdempotencyKeyService.begin()의 CONFLICT/KEY_REUSED에 대응
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "COUPON409-3", "요청이 처리 중입니다. 잠시 후 다시 시도해주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "COUPON409-4", "이미 사용된 Idempotency-Key입니다. 다른 요청에는 새 키를 사용해주세요."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON404-0", "존재하지 않는 쿠폰입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}