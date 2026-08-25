package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

/**
 * 신청 성공 응답 DTO.
 * 발급 신청이 Stream에 발행되기까지만 성공한 시점이라, 실제 CouponIssue 저장(비동기 Consumer)은
 * 아직 안 끝난 상태 — status는 항상 "WAITING"이다. 발급 ID·순번은 그래서 아직 없다.
 */
@Builder
public record CouponIssueCreateResponse(
    Long couponId,
    Long userId,
    String status)
{}
