package com.mycom.petcoupon.event.dto.res;

import java.time.LocalDateTime;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;

import lombok.Builder;

/**
 * 공개 이벤트 상세(GET /events/{eventId}) 응답에 실리는 쿠폰 한 건의 기본정보.
 *
 * 관리자용 {@link com.mycom.petcoupon.coupon.dto.res.CouponListResponse}를 재사용하지 않는다.
 * 그쪽은 eventName·totalQuantity·issuedQuantity 등 관리자 화면 전용 필드를 포함하고,
 * 재고 수치는 DB(coupon_stock) 스냅샷이다. 공개 상세는 "쿠폰이 무엇인지"만 제공하고
 * 실시간 재고는 GET /coupons/{couponId}/status가 따로 맡으므로 재고 필드를 싣지 않는다.
 */
@Builder
public record EventCouponResponse(
		Long couponId,
		String name,
		DiscountType discountType,
		int discountValue,
		int minOrderAmount,
		Integer maxDiscountAmount,
		LocalDateTime issueStartAt,
		LocalDateTime issueEndAt,
		int validDays,
		CouponStatus status
) {
}
