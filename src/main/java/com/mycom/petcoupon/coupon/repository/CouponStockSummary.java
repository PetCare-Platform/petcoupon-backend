package com.mycom.petcoupon.coupon.repository;

// 대시보드 요약 집계(#172)용 인터페이스 프로젝션 — 전체 쿠폰 재고 합계 한 건.
public interface CouponStockSummary {

	Long getTotalQuantity();

	Long getIssuedQuantity();

	Long getRemainingQuantity();
}
