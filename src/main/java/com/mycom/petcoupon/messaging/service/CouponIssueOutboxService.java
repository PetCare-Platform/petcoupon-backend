package com.mycom.petcoupon.messaging.service;

public interface CouponIssueOutboxService {
	
	void saveIfAbsent(Long couponId, Long userId, String requestId, long sequenceNo);
}
