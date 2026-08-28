package com.mycom.petcoupon.coupon.repository;

// 부하 테스트 현황 조회(#195)용 인터페이스 프로젝션 — coupon_issue 한 쿠폰분 요약.
public interface CouponIssueLoadTestSummary {

	long getPassedCount();

	long getDuplicateUserCount();

	// [#200 버그 수정] MySQL엔 진짜 BOOLEAN이 없어 쿼리의 IF(...)가 JDBC로 Long(BIGINT)으로
	// 내려온다. 여기를 boolean으로 선언하면 Spring Data 프로젝션이 Long→boolean 변환기를
	// 못 찾아 UnsupportedOperationException으로 API가 항상 500이 났다. elapsedSeconds와
	// 같은 패턴으로 Long으로 받고, 서비스에서 != 0으로 변환한다.
	Long getSequenceIntact();

	Long getElapsedSeconds();
}
