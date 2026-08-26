package com.mycom.petcoupon.coupon.issue.dto;

import lombok.Builder;

@Builder
public record CouponIssueRealtimeStock(
    boolean initialized,
    int remainingStock
) {}
