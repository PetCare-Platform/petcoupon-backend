package com.mycom.petcoupon.coupon.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;
import com.mycom.petcoupon.coupon.service.CouponIssueDlqReprocessService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/coupon-issue/dlq")
@RequiredArgsConstructor
public class CouponIssueDlqAdminController {

	private final CouponIssueDlqReprocessService couponIssueDlqReprocessService;

	@GetMapping
	public CustomResponse<List<CouponIssueDlqResponse>> listDlqMessages() {
		return CustomResponse.onSuccess(couponIssueDlqReprocessService.listDlqMessages());
	}

	@PostMapping("/{messageId}/reprocess")
	public CustomResponse<CouponIssueDlqReprocessResponse> reprocess(@PathVariable("messageId") Long messageId) {
		return CustomResponse.onSuccess(couponIssueDlqReprocessService.reprocess(messageId));
	}
}
