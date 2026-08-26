package com.mycom.petcoupon.coupon.dto.res;

import java.time.LocalDateTime;

import lombok.Builder;

@Builder
public record CouponIssueDlqResponse(
		Long messageId,
		Long couponId,
		Long userId,
		String requestId,
		int retryCount,
		String lastError,
		LocalDateTime createdAt
) {

}
