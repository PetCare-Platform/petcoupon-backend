package com.mycom.petcoupon.coupon.dto.res;

import java.time.LocalDateTime;

import lombok.Builder;

@Builder
public record CouponIssueRequestResponse(
    Long couponIssueId,
    Long couponId,
    String couponName,
    String couponCode,
    String status,
    LocalDateTime issuedAt,
    LocalDateTime usedAt,
    LocalDateTime expiresAt
) {
}
