package com.mycom.petcoupon.messaging.repository;

// 발급 처리량 조회(#156)용 인터페이스 프로젝션 — 시간대별 집계는 JPQL로 표현이 안 돼서
// (DATE_FORMAT 같은 함수를 그룹 기준으로 못 씀) 네이티브 쿼리를 쓰는데, 컬럼이 여러 개라
// Tuple/Object[] 캐스팅 대신 Spring Data의 인터페이스 프로젝션으로 타입 안전하게 받는다.
public interface IssueThroughputBucket {

    String getBucket();

    Long getIssuedCount();

    Long getFailedCount();

    Long getInProgressCount();
}
