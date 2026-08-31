package com.mycom.petcoupon.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponUpdateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponUpdateResponse;
import com.mycom.petcoupon.coupon.service.CouponService;
import com.mycom.petcoupon.global.common.CustomResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/events")
@RequiredArgsConstructor
public class AdminCouponController {
	private final CouponService couponService;

	@PostMapping("/{eventId}/coupons")
	@ResponseStatus(HttpStatus.CREATED)
	public CustomResponse<CouponCreateResponse> createCoupon(
			@PathVariable("eventId") Long eventId,
			@Valid @RequestBody CouponCreateRequest request
	) {
		CouponCreateResponse response = couponService.createCoupon(eventId, request);

		return CustomResponse.onSuccess(HttpStatus.CREATED, response);
	}

	@PatchMapping("/{eventId}/coupons/{couponId}")
	public CustomResponse<CouponUpdateResponse> updateCoupon(
			@PathVariable("eventId") Long eventId,
			@PathVariable("couponId") Long couponId,
			@Valid @RequestBody CouponUpdateRequest request
	) {
		CouponUpdateResponse response = couponService.updateCoupon(eventId, couponId, request);

		return CustomResponse.onSuccess(response);
	}

}
