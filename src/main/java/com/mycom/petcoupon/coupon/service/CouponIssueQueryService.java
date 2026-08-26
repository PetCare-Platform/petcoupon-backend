package com.mycom.petcoupon.coupon.service;

import java.util.List;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueDetailResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueRequestResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;

public interface CouponIssueQueryService {

    CouponIssueStatusResponse getStatus(Long couponIssueId);

    CouponIssueDetailResponse getDetail(Long couponIssueId);

    List<CouponIssueRequestResponse> getIssueRequests(Long userId, IssueStatus status);
}