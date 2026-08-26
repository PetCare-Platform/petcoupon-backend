package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

@Builder
public record CouponIssueDlqReprocessResponse(
		Long messageId,
		String requestId
) {

}
