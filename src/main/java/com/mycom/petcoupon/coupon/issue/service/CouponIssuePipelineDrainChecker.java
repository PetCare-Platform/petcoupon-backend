package com.mycom.petcoupon.coupon.issue.service;

// 어떤 쿠폰에 대해 발급 파이프라인(Stream → Lua → Outbox → Kafka)에 아직 처리 안 된 요청이
// 남아 있는지 확인한다. InternalCouponResetServiceImpl(초기화)과 ReconciliationServiceImpl
// (정합성 검증) 둘 다, "지금 이 쿠폰을 건드려도 안전한가"를 판단할 때 이 신호가 필요해서 공유한다.
public interface CouponIssuePipelineDrainChecker {

    PipelineDrainStatus check(Long couponId);
}
