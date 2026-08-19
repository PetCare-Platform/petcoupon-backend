package com.mycom.petcoupon.coupon.dto.res;

/**
 * 신청 성공 응답 DTO.
 * 지금 단계(Redis mock)는 DB에 CouponIssue를 안 만들기 때문에
 * 발급 ID·순번 없이 couponId, userId만 돌려준다.
 */
public record CouponIssueCreateResponse(
    Long couponId,
    Long userId)
{}
