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
	
	INVALID_ISSUE_REQUEST(HttpStatus.BAD_REQUEST, "COUPON400-0", "쿠폰 신청 요청값이 올바르지 않습니다."),
	INVALID_EVENT_STATUS(HttpStatus.BAD_REQUEST, "COUPON400-1", "쿠폰을 생성할 수 없는 이벤트 상태입니다."),
	INVALID_ISSUE_PERIOD(HttpStatus.BAD_REQUEST, "COUPON400-2", "쿠폰 발급 종료 시각은 시작 시각보다 이후여야 합니다."),
	ISSUE_PERIOD_OUT_OF_EVENT_PERIOD(HttpStatus.BAD_REQUEST, "COUPON400-3", "쿠폰 발급 기간은 이벤트 기간 내에 있어야 합니다."),
	INVALID_RATE_DISCOUNT_POLICY(HttpStatus.BAD_REQUEST, "COUPON400-4", "정률 할인 정책이 올바르지 않습니다."),
	INVALID_FIXED_AMOUNT_DISCOUNT_POLICY(HttpStatus.BAD_REQUEST, "COUPON400-5", "정액 할인에는 최대 할인 금액을 설정할 수 없습니다."),
	INVALID_EVENT_STATUS_FOR_UPDATE(HttpStatus.BAD_REQUEST, "COUPON400-6", "이벤트가 예정 상태일 때만 쿠폰을 수정할 수 있습니다."),
	INVALID_COUPON_STATUS_FOR_UPDATE(HttpStatus.BAD_REQUEST, "COUPON400-7", "발급 대기 상태의 쿠폰만 수정할 수 있습니다."),
	EMPTY_UPDATE_REQUEST(HttpStatus.BAD_REQUEST, "COUPON400-8", "수정할 항목이 없습니다."),
	ISSUE_ALREADY_STARTED(HttpStatus.BAD_REQUEST, "COUPON400-9", "발급이 시작된 쿠폰은 수정할 수 없습니다."),

    SOLD_OUT(HttpStatus.CONFLICT, "COUPON409-0", "쿠폰 재고가 모두 소진되었습니다."),
    DUPLICATE_USER(HttpStatus.CONFLICT, "COUPON409-1", "이미 발급받은 쿠폰입니다."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "COUPON409-2", "이미 처리된 요청입니다."),
    TOTAL_QUANTITY_UPDATE_NOT_ALLOWED(HttpStatus.CONFLICT, "COUPON409-4", "발급이 시작된 쿠폰은 총 수량을 수정할 수 없습니다."),

    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON404-0", "존재하지 않는 쿠폰입니다."),
    COUPON_ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON404-1", "발급 내역을 찾을 수 없습니다."),
    COUPON_ISSUE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON404-2", "해당 Idempotency-Key로 신청된 발급 요청을 찾을 수 없습니다."),
    NOT_COUPON_OWNER(HttpStatus.FORBIDDEN, "COUPON403-0", "본인의 쿠폰이 아닙니다."),
    INVALID_ISSUE_STATUS(HttpStatus.CONFLICT, "COUPON409-3", "현재 상태에서는 처리할 수 없습니다."),

    // 아래 2개는 이슈 #16(Idempotency-Key) 추가분 — IdempotencyKeyService.begin()의 CONFLICT/KEY_REUSED에 대응
    // COUPON409-3은 PR #28(박신형, INVALID_ISSUE_STATUS)이 먼저 씀 — 겹쳐서 409-5/6으로 옮김
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "COUPON409-5", "요청이 처리 중입니다. 잠시 후 다시 시도해주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "COUPON409-6", "이미 사용된 Idempotency-Key입니다. 다른 요청에는 새 키를 사용해주세요."),

    ISSUE_REQUEST_SAVE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "COUPON503-0", "쿠폰 신청 요청을 처리하지 못했습니다."),
	ISSUE_REDIS_STATE_CLEAR_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "COUPON503-1", "쿠폰 발급 Redis 상태를 초기화하지 못했습니다."),
	ISSUE_OUTBOX_SAVE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "COUPON503-2", "쿠폰 발급 이벤트 저장에 실패했습니다."),
	ISSUE_CONFIRMATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "COUPON503-3", "쿠폰 발급 확정에 실패했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}