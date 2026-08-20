package com.mycom.petcoupon.coupon.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.service.CouponIssueService;
import com.mycom.petcoupon.global.common.CustomResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * 선착순 쿠폰 신청 API 진입점.
 * 실패 응답은 여기서 직접 안 만든다 — service가 던진 GeneralException을
 * GlobalExceptionHandler가 잡아서 CustomResponse.onFailure(...)로 변환한다.
 */
@RestController // @RequestMapping만 있으면 컨트롤러 빈으로 등록도, JSON 직렬화도 안 됨
@Validated // @PathVariable 등 메서드 파라미터에 붙은 @Positive 같은 제약을 실제로 검증하려면 필요
@RequiredArgsConstructor
public class CouponController {

    private final CouponIssueService couponIssueService;

    @PostMapping("/coupons/{couponId}/issues")
    public CustomResponse<CouponIssueCreateResponse> issue(
        @PathVariable("couponId")
        @Positive Long 
        couponId,
        @Valid @RequestBody CouponIssueCreateRequest request) {
            // 성공 케이스만 여기서 처리. 재고소진/중복은 service에서 예외로 던져짐
            CouponIssueCreateResponse response = couponIssueService.issue(couponId, request);
            return CustomResponse.onSuccess(response);
        }

}
