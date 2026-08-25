package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

/**
 * 신청 성공 응답 DTO.
 * 발급 신청이 Stream에 발행되기까지만 성공한 시점이라, 실제 CouponIssue 저장(비동기 Consumer)은
 * 아직 안 끝난 상태 — status는 항상 "WAITING"이다.
 * couponIssueId·sequenceNo는 비동기 파이프라인(Kafka Consumer가 CouponIssue를 실제로 저장한 뒤)에서만
 * 채워진다 — 순번은 Lua가 재고를 선점하는 순간(비동기)에 정해지므로 접수 시점엔 알 수 없다.
 */
@Builder
public record CouponIssueCreateResponse(
    Long couponIssueId,
    Long couponId,
    Long userId,
    Long sequenceNo,
    String status)
{}
