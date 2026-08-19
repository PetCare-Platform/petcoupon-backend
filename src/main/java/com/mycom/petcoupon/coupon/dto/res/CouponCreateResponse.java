package com.mycom.petcoupon.coupon.dto.res;

import java.time.LocalDateTime;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;

public record CouponCreateResponse(
		Long couponId,
		Long eventId,
		String name,
		DiscountType discountType,
		int discountValue,
		int minOrderAmount,
		Integer maxDiscountAmount,
		LocalDateTime issueStartAt,
		LocalDateTime issueEndAt,
		int validDays,
		int totalQuantity,
		CouponStatus status
) {
}
