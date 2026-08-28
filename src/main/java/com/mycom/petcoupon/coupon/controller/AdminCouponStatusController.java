package com.mycom.petcoupon.coupon.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.res.CouponFailureReasonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponLoadTestStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.service.CouponFailureReasonService;
import com.mycom.petcoupon.coupon.service.CouponLoadTestStatusService;
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
    private final CouponLoadTestStatusService couponLoadTestStatusService;
    private final CouponFailureReasonService couponFailureReasonService;

    @GetMapping("/{couponId}/status")
    public CustomResponse<CouponRealtimeStatusResponse> getRealtimeStatus(
            @PathVariable("couponId") @Positive Long couponId
    ) {
        CouponRealtimeStatusResponse response = couponRealtimeStatusService.getRealtimeStatus(couponId);

        return CustomResponse.onSuccess(response);
    }

    // 부하 테스트 현황 조회(#195) — 대시보드가 5초 간격으로 폴링한다.
    @GetMapping("/{couponId}/load-test-status")
    public CustomResponse<CouponLoadTestStatusResponse> getLoadTestStatus(
            @PathVariable("couponId") @Positive Long couponId
    ) {
        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(couponId);

        return CustomResponse.onSuccess(response);
    }

    // 실패 사유 분류 집계(#195).
    @GetMapping("/{couponId}/failure-reasons")
    public CustomResponse<CouponFailureReasonResponse> getFailureReasons(
            @PathVariable("couponId") @Positive Long couponId
    ) {
        CouponFailureReasonResponse response = couponFailureReasonService.getFailureReasons(couponId);

        return CustomResponse.onSuccess(response);
    }
}
