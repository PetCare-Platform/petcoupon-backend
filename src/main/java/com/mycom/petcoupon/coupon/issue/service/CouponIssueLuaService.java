package com.mycom.petcoupon.coupon.issue.service;

import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;

public interface CouponIssueLuaService {
	
	CouponIssueLuaResultStatus issue(Long couponId, Long userId, String requestId);
}
