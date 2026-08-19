package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;

public interface CouponIssueService {
    CouponIssueCreateResponse issue(Long couponId, CouponIssueCreateRequest request);
}
