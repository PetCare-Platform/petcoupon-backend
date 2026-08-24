package com.mycom.petcoupon.coupon.issue.service;

import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;

public interface CouponIssueLuaService {
	
	CouponIssueLuaResult issue(Long couponId, Long userId, String requestId);
	
	void clearIssueState(Long couponId);
}
