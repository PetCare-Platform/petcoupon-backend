package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

@Builder
public record CouponRealtimeStatusResponse(
    Long couponId,
    int totalQuantity,
    int remainingQuantity,
    int issuedQuantity,
    boolean initialized
) {}
