package com.mycom.petcoupon.coupon.dto.res;

import java.time.LocalDateTime;

import lombok.Builder;

@Builder
public record CouponIssueDetailResponse(
    Long couponIssueId,
    String couponCode,
    String status,    // DB 원본 그대로(ISSUED/USED/CANCELED/EXPIRED)
    boolean isUsable, // 계산값: status=="ISSUED" && expiresAt > 현재시각
    LocalDateTime usedAt,
    LocalDateTime expiresAt,
    LocalDateTime createdAt
) {

}
