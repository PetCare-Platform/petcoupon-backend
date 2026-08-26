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
 *
 * <p><b>호출 전 선행 조건 — 앞 회차 메시지가 모두 처리돼 있어야 한다.</b>
 * 이 API 가 되돌리는 것은 DB 와 Redis 발급 상태뿐이다. Redis Stream 에 남은 미배달·pending
 * 메시지와 Kafka 에 이미 발행된 메시지는 지우지 못한다. 특히 Outbox 발행은
 * {@code kafkaTemplate.send()} 가 DB 트랜잭션 밖에서 일어나므로, 여기서 {@code issue_message}
 * 행을 지워도 이미 브로커로 나간 메시지는 되돌릴 수 없다.
 *
 * <p>그 상태로 초기화하면 지난 회차 신청이 뒤늦게 처리되면서 <b>이번 회차 재고를 깎는다.</b>
 * 초기화가 {@code coupon_issue} 를 모두 지운 뒤라 유니크 제약도 이를 막지 못한다.
 * 호출 전에 확인할 네 가지 값과 명령은 {@code load-test/README.md} 의 "초기화" 항목에 있다.
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
