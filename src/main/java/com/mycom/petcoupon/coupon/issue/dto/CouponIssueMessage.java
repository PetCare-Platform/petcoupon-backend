package com.mycom.petcoupon.coupon.issue.dto;

// Redis Stream 에 저장된 쿠폰 발급 요청 메시지를 다루기 위한 DTO 
public record CouponIssueMessage(
		String requestId,
		Long couponId,
		Long userId
) {}
