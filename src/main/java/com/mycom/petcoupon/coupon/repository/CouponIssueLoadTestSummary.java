package com.mycom.petcoupon.coupon.repository;

// 부하 테스트 현황 조회(#195)용 인터페이스 프로젝션 — coupon_issue 한 쿠폰분 요약.
public interface CouponIssueLoadTestSummary {

	long getPassedCount();

	long getDuplicateUserCount();

	boolean getSequenceIntact();

	Long getElapsedSeconds();
}
