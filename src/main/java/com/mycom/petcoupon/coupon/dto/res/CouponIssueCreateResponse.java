package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

/**
 * 신청 응답 및 확정 응답 DTO.
 * 1. 접수 시점: 발급 신청이 Stream에 발행되기까지만 성공한 시점이라 status는 "WAITING",
 *    couponIssueId·sequenceNo는 null이다.
 * 2. 확정 시점(폴링 결과): 비동기 Consumer/Persister가 DB 저장을 완료한 뒤 status는 "ISSUED",
 *    couponIssueId·sequenceNo가 채워진다.
 */
@Builder
public record CouponIssueCreateResponse(
    Long couponIssueId,
    Long couponId,
    Long userId,
    Long sequenceNo,
    String status)
{}
