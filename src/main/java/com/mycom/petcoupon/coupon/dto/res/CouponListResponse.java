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
 * 재고 갱신 시각(stockUpdatedAt = coupon_stock.updated_at)을 함께 싣는다. 발급 확정에 쓰는
 * increaseIssuedQuantity가 벌크 UPDATE라 @LastModifiedDate가 개입하지 않는 문제가 있었는데
 * (이슈 #146), 그 메서드가 updatedAt을 직접 갱신하도록 고친 뒤에야 이 필드를 실었다.
 * 총수량 수정으로만 바뀐 값이면(재고가 아직 한 건도 안 나간 쿠폰) 쿠폰 생성·수정 시각과 같을 수 있다.
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
