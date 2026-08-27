package com.mycom.petcoupon.coupon.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.req.CouponFilterRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponPageResponse;
import com.mycom.petcoupon.coupon.service.CouponQueryService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

/**
 * 등록된 쿠폰 전체를 페이지 단위로 조회한다. 이벤트를 몰라도 쿠폰을 찾을 수 있게 하는 게 목적이라
 * 이벤트 하위(/admin/events/{eventId}/coupons)가 아니라 최상위 경로에 둔다.
 *
 * eventId·status는 선택 필터이고, 미지정이면 전체를 조회한다.
 */
@RestController
@RequestMapping("/admin/coupons")
@RequiredArgsConstructor
public class AdminCouponQueryController {

    private final CouponQueryService couponQueryService;

    // 네 파라미터를 모두 String으로 받는 이유는 AdminEventController와 같다. Long·enum·int로 받으면
    // 잘못된 값이 스프링 바인딩 단계에서 걸려 도메인 에러 코드를 실을 수 없다.
    // 파싱과 검증은 CouponFilterRequest·CouponPageRequest가 맡는다.
    @GetMapping
    public CustomResponse<CouponPageResponse> getCoupons(
            @RequestParam(name = "eventId", required = false) String eventId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = CouponPageRequest.DEFAULT_PAGE) String page,
            @RequestParam(name = "size", defaultValue = CouponPageRequest.DEFAULT_SIZE) String size
    ) {
        CouponPageResponse response = couponQueryService.getCoupons(
                CouponFilterRequest.from(eventId, status),
                CouponPageRequest.from(page, size)
        );

        return CustomResponse.onSuccess(response);
    }
}
