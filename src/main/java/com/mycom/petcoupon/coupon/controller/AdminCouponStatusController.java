package com.mycom.petcoupon.coupon.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueTimeSeriesResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponPipelineDrainStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.service.CouponRealtimeStatusService;
import com.mycom.petcoupon.global.common.CustomResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/coupons")
@Validated
@RequiredArgsConstructor
public class AdminCouponStatusController {

    private final CouponRealtimeStatusService couponRealtimeStatusService;

    @GetMapping("/{couponId}/status")
    public CustomResponse<CouponRealtimeStatusResponse> getRealtimeStatus(
            @PathVariable("couponId") @Positive Long couponId
    ) {
        CouponRealtimeStatusResponse response = couponRealtimeStatusService.getRealtimeStatus(couponId);

        return CustomResponse.onSuccess(response);
    }

    @GetMapping("/{couponId}/pipeline-drain-status")
    public CustomResponse<CouponPipelineDrainStatusResponse> getPipelineDrainStatus(
            @PathVariable("couponId") @Positive Long couponId
    ) {
        CouponPipelineDrainStatusResponse response = couponRealtimeStatusService.getPipelineDrainStatus(couponId);

        return CustomResponse.onSuccess(response);
    }

    @GetMapping("/{couponId}/issue-timeseries")
    public CustomResponse<CouponIssueTimeSeriesResponse> getIssueTimeSeries(
            @PathVariable("couponId") @Positive Long couponId,
            @RequestParam(name = "windowSeconds", defaultValue = "90") @Min(1) @Max(3600) int windowSeconds,
            @RequestParam(name = "bucketSeconds", defaultValue = "5") @Min(1) @Max(300) int bucketSeconds
    ) {
        CouponIssueTimeSeriesResponse response = couponRealtimeStatusService.getIssueTimeSeries(
                couponId, windowSeconds, bucketSeconds
        );

        return CustomResponse.onSuccess(response);
    }
}
