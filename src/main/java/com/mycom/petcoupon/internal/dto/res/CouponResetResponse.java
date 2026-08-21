package com.mycom.petcoupon.internal.dto.res;

import lombok.Builder;

/**
 * 초기화 결과. k6 setup 에서 초기화가 실제로 수행됐는지 확인하는 데 쓴다.
 */
@Builder
public record CouponResetResponse(
		Long couponId,
		long deletedHistories,
		long deletedIdempotencyKeys,
		long deletedIssues,
		long deletedMessages,
		int totalQuantity,
		int remainingQuantity
) {
}
