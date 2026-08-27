package com.mycom.petcoupon.coupon.dto.req;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

/**
 * 쿠폰 목록의 선택 필터. 둘 다 null이면 전체 조회다.
 *
 * eventId·status를 컨트롤러에서 Long·enum으로 바로 받지 않는 이유는 CouponPageRequest와 같다.
 * 잘못된 값이 스프링 바인딩 단계에서 걸리면 GlobalExceptionHandler에 그 예외를 받는 자리가 없어
 * 500으로 나간다. 전역 처리를 건드리는 대신 도메인 안에서 파싱해 쿠폰 에러 코드로 답한다.
 */
public record CouponFilterRequest(
        Long eventId,
        CouponStatus status
) {
    public static CouponFilterRequest from(String eventId, String status) {
        return new CouponFilterRequest(parseEventId(eventId), parseStatus(status));
    }

    private static Long parseEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }

        try {
            long parsed = Long.parseLong(eventId);

            // 식별자는 1부터 시작한다. 0이나 음수는 존재할 수 없는 값이라 조회 전에 거른다.
            if (parsed <= 0) {
                throw new GeneralException(CouponErrorCode.INVALID_COUPON_FILTER);
            }

            return parsed;
        } catch (NumberFormatException e) {
            throw new GeneralException(CouponErrorCode.INVALID_COUPON_FILTER);
        }
    }

    private static CouponStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        try {
            return CouponStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new GeneralException(CouponErrorCode.INVALID_COUPON_FILTER);
        }
    }
}
