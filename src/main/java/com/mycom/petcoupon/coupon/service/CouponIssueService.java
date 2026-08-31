package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;

public interface CouponIssueService {
    // requestId는 컨트롤러가 Idempotency-Key(recordId 기반, "issue:{recordId}")로 미리 만들어 넘긴 전역 유일 값.
    // Stream 메시지의 requestId로 그대로 쓰인다(CouponIssueServiceImpl 참고).
    CouponIssueCreateResponse issue(Long couponId, CouponIssueCreateRequest request, String requestId);
}
