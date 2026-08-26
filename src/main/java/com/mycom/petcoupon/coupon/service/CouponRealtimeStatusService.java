package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;

public interface CouponRealtimeStatusService {

    CouponRealtimeStatusResponse getRealtimeStatus(Long couponId);
}
