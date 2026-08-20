package com.mycom.petcoupon.coupon.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.service.CouponIssueQueryService;
import com.mycom.petcoupon.global.common.CustomResponse;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequiredArgsConstructor
public class CouponIssueController {

    private final CouponIssueQueryService couponIssueQueryService;

    @GetMapping("/coupon-issues/{couponIssueId}/status")
    public CustomResponse<CouponIssueStatusResponse> getCouponIssueStatus(@PathVariable @Positive Long couponIssueId) {
        CouponIssueStatusResponse response = couponIssueQueryService.getStatus(couponIssueId);
        return CustomResponse.onSuccess(response);
    }
}