package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;

public interface CouponIssueService {
    // idempotencyKey는 컨트롤러가 헤더에서 그대로 받아 넘긴 값. Redis 재고 차감/발행의 requestId로 재사용된다
    // (CouponIssueServiceImpl 참고) — 이전엔 매번 새 UUID를 써서 죽은 시도가 reclaim될 때 같은 요청인지 구분이 안 됐다.
    CouponIssueCreateResponse issue(Long couponId, CouponIssueCreateRequest request, String idempotencyKey);
}
