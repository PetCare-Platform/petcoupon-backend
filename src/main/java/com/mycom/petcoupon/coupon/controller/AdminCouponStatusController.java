package com.mycom.petcoupon.coupon.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.res.CouponPipelineDrainStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.service.CouponRealtimeStatusService;
import com.mycom.petcoupon.global.common.CustomResponse;

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
}
