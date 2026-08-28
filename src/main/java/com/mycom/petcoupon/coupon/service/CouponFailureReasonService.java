package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.res.CouponFailureReasonResponse;

public interface CouponFailureReasonService {

    CouponFailureReasonResponse getFailureReasons(Long couponId);
}
