package com.mycom.petcoupon.coupon.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.CouponIssue;

@Component
public class CouponIssueConverter {

    public CouponIssueStatusResponse toStatusResponse(CouponIssue couponIssue, boolean isUsable) {
        return CouponIssueStatusResponse.builder()
                .status(couponIssue.getStatus().name())
                .isUsable(isUsable)
                .expiresAt(couponIssue.getExpiresAt())
                .build();
    }
}