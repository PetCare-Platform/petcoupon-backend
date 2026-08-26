package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

@Builder
public record CouponIssueDlqAbandonResponse(
		Long messageId,
		String requestId,
		String restoreStatus,
		int remainingStock
) {

}
