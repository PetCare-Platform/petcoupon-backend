package com.mycom.petcoupon.internal.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.internal.dto.req.CouponResetRequest;
import com.mycom.petcoupon.internal.dto.res.CouponResetResponse;
import com.mycom.petcoupon.internal.service.InternalCouponResetService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 부하 테스트 전용 내부 API.
 *
 * <p>k6 는 HTTP 요청만 보낼 수 있어 DB 에 직접 접근하지 못한다.
 * 매 회차 초기화를 자동화하기 위해 API 로 제공한다.
 *
 * <p>운영에 노출되면 쿠폰 재고를 임의로 리셋할 수 있게 되므로
 * {@code prod} 프로필에서는 빈으로 등록되지 않는다.
 */
@RestController
@RequestMapping("/internal/coupons")
@RequiredArgsConstructor
@Profile("!prod")
public class InternalCouponController {

	private final InternalCouponResetService internalCouponResetService;

	@PostMapping("/{couponId}/reset")
	public CustomResponse<CouponResetResponse> reset(
			@PathVariable("couponId") Long couponId,
			@Valid @RequestBody CouponResetRequest request
	) {
		CouponResetResponse response = internalCouponResetService.reset(couponId, request);

		return CustomResponse.onSuccess(response);
	}
}
