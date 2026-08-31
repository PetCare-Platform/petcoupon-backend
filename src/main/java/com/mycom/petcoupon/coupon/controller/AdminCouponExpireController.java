package com.mycom.petcoupon.coupon.controller;

import java.time.LocalDateTime;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.res.CouponExpireBatchResponse;
import com.mycom.petcoupon.coupon.service.CouponExpireBatchService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/coupons")
@RequiredArgsConstructor
public class AdminCouponExpireController {

    private final CouponExpireBatchService couponExpireBatchService;

    @PostMapping("/expire")
    public CustomResponse<CouponExpireBatchResponse> triggerExpireBatch() {
        int expiredCount = couponExpireBatchService.expireOverdueCoupons();

        CouponExpireBatchResponse response = CouponExpireBatchResponse.builder()
                .expiredCount(expiredCount)
                .executedAt(LocalDateTime.now())
                .build();

        return CustomResponse.onSuccess(response);
    }
}
