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
		long deletedNotifications,
		long deletedIssues,
		long deletedMessages,
		long deletedReports,
		int totalQuantity,
		int remainingQuantity,

		/** 초기화 후 Redis 에 다시 넣은 재고. DB 재고와 어긋나면 여기서 바로 드러난다. */
		int redisStock
) {
}
