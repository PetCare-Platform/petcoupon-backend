package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponUpdateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponUpdateResponse;

public interface CouponService {
	CouponCreateResponse createCoupon(Long eventId, CouponCreateRequest request);

	CouponUpdateResponse updateCoupon(Long eventId, Long couponId, CouponUpdateRequest request);
}
