package com.mycom.petcoupon.coupon.dto.res;

import java.time.LocalDateTime;

import lombok.Builder;

@Builder
public record CouponExpireBatchResponse(
		int expiredCount,
		LocalDateTime executedAt
) {
}
