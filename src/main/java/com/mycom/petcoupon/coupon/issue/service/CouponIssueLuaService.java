package com.mycom.petcoupon.coupon.issue.service;

import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueStockRestoreResult;

public interface CouponIssueLuaService {

	CouponIssueLuaResult issue(Long couponId, Long userId, String requestId);

	void clearIssueState(Long couponId);

	CouponIssueRealtimeStock getRealtimeStock(Long couponId);
	
	CouponIssueStockRestoreResult restoreStock(Long couponId, Long userId, String requestId, Long sequenceNo);
}
