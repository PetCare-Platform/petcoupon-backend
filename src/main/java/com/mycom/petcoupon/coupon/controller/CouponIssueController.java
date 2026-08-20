package com.mycom.petcoupon.coupon.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.req.CouponIssueUseRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.service.CouponIssueQueryService;
import com.mycom.petcoupon.coupon.service.CouponIssueUseService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CouponIssueController {

    private final CouponIssueQueryService couponIssueQueryService;
    private final CouponIssueUseService couponIssueUseService;

    @GetMapping("/coupon-issues/{couponIssueId}/status")
    public CustomResponse<CouponIssueStatusResponse> getCouponIssueStatus(@PathVariable Long couponIssueId) {
        CouponIssueStatusResponse response = couponIssueQueryService.getStatus(couponIssueId);
        return CustomResponse.onSuccess(response);
    }
    
    @PostMapping("/coupon-issues/{couponIssueId}/use")
    public CustomResponse<Void> useCouponIssue(
            @PathVariable Long couponIssueId,
            @RequestBody CouponIssueUseRequest request
    ) {
        couponIssueUseService.use(couponIssueId, request.userId());
        return CustomResponse.onSuccess(null);
    }
}