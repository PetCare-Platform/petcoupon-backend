package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;

public interface CouponService {
	CouponCreateResponse createCoupon(Long eventId, CouponCreateRequest request);
}
