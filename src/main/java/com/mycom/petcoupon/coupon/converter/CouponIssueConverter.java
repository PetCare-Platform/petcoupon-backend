package com.mycom.petcoupon.coupon.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.CouponIssue;

/**
 * Entity <-> DTO 변환 전담 클래스.
 * 나중에 CouponIssue 엔티티로 발급 생성을 저장하게 되면 toCreateResponse(CouponIssue)로 바꾸면 됨.
 */
@Component
public class CouponIssueConverter {

    public CouponIssueCreateResponse toCreateResponse(Long couponId, Long userId) {
        return CouponIssueCreateResponse.builder()
                .couponId(couponId)
                .userId(userId)
                .build();
    }

    public CouponIssueStatusResponse toStatusResponse(CouponIssue couponIssue, boolean isUsable) {
        return CouponIssueStatusResponse.builder()
                .status(couponIssue.getStatus().name())
                .isUsable(isUsable)
                .expiresAt(couponIssue.getExpiresAt())
                .build();
    }
}
