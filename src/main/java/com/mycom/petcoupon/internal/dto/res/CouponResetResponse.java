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

		/**
		 * 초기화 후 <b>Redis 에서 다시 읽은</b> 재고. 쓴 값을 되돌려주는 것이 아니라 실제로 저장된 값이라,
		 * {@code totalQuantity} 와 다르면 Redis 초기화가 제대로 안 된 것이다.
		 * 키를 읽지 못했거나 값이 숫자가 아니면 {@code null} 이며, 이때도 초기화 실패로 본다.
		 */
		Integer redisStock
) {
}
