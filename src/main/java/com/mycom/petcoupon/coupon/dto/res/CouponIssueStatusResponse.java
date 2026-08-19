package com.mycom.petcoupon.coupon.dto.res;

import java.time.LocalDateTime;

import lombok.Builder;

@Builder
public record CouponIssueStatusResponse(
        String status,
        boolean isUsable,
        LocalDateTime expiresAt
) {
	
}