package com.mycom.petcoupon.coupon.issue.dto;

import java.time.LocalDateTime;

// Outbox(issue_message.payload)에 직렬화되어 저장되고, Kafka로 발행되는 쿠폰 발급 확정 이벤트
// couponCode/expiresAt: Outbox 최초 저장 시점(Lua 성공 직후)에 생성되어 전달됨
public record CouponIssueEvent(
		Long couponId,
		Long userId,
		String requestId,
		long sequenceNo,
		String couponCode,
		LocalDateTime expiresAt
) {}
