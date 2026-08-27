package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.req.CouponFilterRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponPageResponse;

public interface CouponQueryService {

    CouponPageResponse getCoupons(CouponFilterRequest filterRequest, CouponPageRequest pageRequest);
}
