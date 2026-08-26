package com.mycom.petcoupon.coupon.issue.dto;

import lombok.Builder;

@Builder
public record CouponIssueRealtimeStock(
    int remainingStock,
    int issuedCount
) {}
