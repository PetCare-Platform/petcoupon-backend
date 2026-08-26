package com.mycom.petcoupon.coupon.dto.res;

import java.time.LocalDateTime;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;

import lombok.Builder;

/**
 * 관리자 쿠폰 목록의 한 줄.
 *
 * 재고 수치는 Redis 실시간 값이 아니라 DB(coupon_stock) 기준이다. 목록에서 쿠폰마다
 * Redis를 조회하면 20건 목록에 왕복이 20회 생기고, 쿠폰 한 건의 정합성 오류로 페이지
 * 전체가 실패한다. 실시간 재고는 단건 조회(GET /admin/coupons/{couponId}/status)가 맡는다.
 *
 * 그래서 이 수치가 언제 기준인지 알 수 있도록 stockUpdatedAt(coupon_stock.updated_at)을 함께 싣는다.
 */
@Builder
public record CouponListResponse(
        Long couponId,
        Long eventId,
        String eventName,
        String name,
        DiscountType discountType,
        int discountValue,
        int minOrderAmount,
        Integer maxDiscountAmount,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        int validDays,
        CouponStatus status,
        int totalQuantity,
        int issuedQuantity,
        int remainingQuantity,
        LocalDateTime stockUpdatedAt
) {
}
