package com.mycom.petcoupon.dashboard.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.repository.CouponIssueStatusCount;
import com.mycom.petcoupon.dashboard.dto.res.CouponIssueStatusDistributionResponse;

@Component
public class DashboardSummaryConverter {

    public CouponIssueStatusDistributionResponse toDistributionResponse(CouponIssueStatusCount count) {
        return CouponIssueStatusDistributionResponse.builder()
                .status(count.getStatus())
                .count(count.getCount())
                .build();
    }
}
