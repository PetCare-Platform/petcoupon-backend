package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.res.CouponLoadTestStatusResponse;

public interface CouponLoadTestStatusService {

    CouponLoadTestStatusResponse getLoadTestStatus(Long couponId);
}
