package com.mycom.petcoupon.coupon.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CouponIssueUseRequest(
		@NotNull @Positive Long userId
) {
	
}
