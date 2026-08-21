package com.mycom.petcoupon.coupon.service;

public interface CouponIssueCancelService {

	void cancelUsage(Long couponIssueId, Long userId);
}
