package com.mycom.petcoupon.coupon.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqAbandonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqPageResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.service.CouponIssueDlqReprocessService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/coupon-issue/dlq")
@RequiredArgsConstructor
public class CouponIssueDlqAdminController {

	private final CouponIssueDlqReprocessService couponIssueDlqReprocessService;

	// page/size를 String으로 받아 CouponPageRequest.from()에 위임하는 이유는
	// AdminCouponQueryController와 같다 — 잘못된 값이 스프링 바인딩 단계에서 걸리면 도메인
	// 에러 코드를 못 실어서다. CouponPageRequest는 쿠폰 목록 페이지네이션(#106)용으로 이미
	// 있던 걸 그대로 재사용한다 — page/size 검증 규칙(0 이상, size는 10/20/50/100)이 여기도
	// 똑같이 적용돼서 새로 만들 이유가 없다.
	@GetMapping
	public CustomResponse<CouponIssueDlqPageResponse> listDlqMessages(
			@RequestParam(name = "page", defaultValue = CouponPageRequest.DEFAULT_PAGE) String page,
			@RequestParam(name = "size", defaultValue = CouponPageRequest.DEFAULT_SIZE) String size
	) {
		return CustomResponse.onSuccess(
				couponIssueDlqReprocessService.listDlqMessages(CouponPageRequest.from(page, size))
		);
	}

	@PostMapping("/{messageId}/reprocess")
	public CustomResponse<CouponIssueDlqReprocessResponse> reprocess(@PathVariable("messageId") Long messageId) {
		return CustomResponse.onSuccess(couponIssueDlqReprocessService.reprocess(messageId));
	}

	@PostMapping("/{messageId}/abandon")
	public CustomResponse<CouponIssueDlqAbandonResponse> abandon(@PathVariable("messageId") Long messageId) {
		return CustomResponse.onSuccess(couponIssueDlqReprocessService.abandon(messageId));
	}
}
