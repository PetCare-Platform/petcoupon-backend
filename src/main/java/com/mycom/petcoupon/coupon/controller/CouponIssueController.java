package com.mycom.petcoupon.coupon.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.req.CouponIssueCancelRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueUseRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDetailResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueRequestResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.service.CouponIssueCancelService;
import com.mycom.petcoupon.coupon.service.CouponIssueQueryService;
import com.mycom.petcoupon.coupon.service.CouponIssueUseService;
import com.mycom.petcoupon.global.common.CustomResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequiredArgsConstructor
public class CouponIssueController {

    private final CouponIssueQueryService couponIssueQueryService;
    private final CouponIssueUseService couponIssueUseService;
    private final CouponIssueCancelService couponIssueCancelService;

    // 사용자 본인의 쿠폰 발급 신청 내역 전체를 최신순으로 조회 (건당 상세/상태 조회와 달리 목록 API)
    @GetMapping("/users/{userId}/coupon-issue-requests")
    public CustomResponse<List<CouponIssueRequestResponse>> getCouponIssueRequests(
            @PathVariable("userId") @Positive Long userId) {
        List<CouponIssueRequestResponse> response = couponIssueQueryService.getIssueRequests(userId);
        return CustomResponse.onSuccess(response);
    }

    @GetMapping("/coupon-issues/{couponIssueId}")
    public CustomResponse<CouponIssueDetailResponse> getCouponIssueDetail(
            @PathVariable("couponIssueId") @Positive Long couponIssueId) {
        CouponIssueDetailResponse response = couponIssueQueryService.getDetail(couponIssueId);
        return CustomResponse.onSuccess(response);
    }

    @GetMapping("/coupon-issues/{couponIssueId}/status")
    public CustomResponse<CouponIssueStatusResponse> getCouponIssueStatus(
            @PathVariable("couponIssueId") @Positive Long couponIssueId) {
        CouponIssueStatusResponse response = couponIssueQueryService.getStatus(couponIssueId);
        return CustomResponse.onSuccess(response);
    }

    @PostMapping("/coupon-issues/{couponIssueId}/use")
    public CustomResponse<Void> useCouponIssue(
            @PathVariable("couponIssueId") @Positive Long couponIssueId,
            @Valid @RequestBody CouponIssueUseRequest request
    ) {
        couponIssueUseService.use(couponIssueId, request.userId());
        return CustomResponse.onSuccess(null);
    }

    @PostMapping("/coupon-issues/{couponIssueId}/cancel")
    public CustomResponse<Void> cancelCouponIssue(
            @PathVariable("couponIssueId") @Positive Long couponIssueId,
            @Valid @RequestBody CouponIssueCancelRequest request
    ) {
        couponIssueCancelService.cancelUsage(couponIssueId, request.userId());
        return CustomResponse.onSuccess(null);
    }
}