package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueDetailResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;

public interface CouponIssueQueryService {

    CouponIssueStatusResponse getStatus(Long couponIssueId);

    CouponIssueDetailResponse getDetail(Long couponIssueId);
}