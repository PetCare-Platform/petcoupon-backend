package com.mycom.petcoupon.reconciliation.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;

public interface VerificationDetailRepository extends JpaRepository<VerificationDetail, Long> {

    long countByReport_ReportId(Long reportId);

    @Query("""
            SELECT COUNT(DISTINCT v.couponIssueId) FROM VerificationDetail v
             WHERE v.report.reportId = :reportId AND v.couponIssueId IS NOT NULL
            """)
    long countDistinctCouponIssueId(@Param("reportId") Long reportId);

    // 응답에 담을 상위 N건만 골라온다 — 몇 건을 자를지는 호출부(ReconciliationConverter의
    // MAX_DETAILS_IN_RESPONSE)가 Pageable로 넘긴다. 전체를 로딩한 뒤 자르지 않기 위함.
    List<VerificationDetail> findByReport_ReportIdOrderByDetailIdAsc(Long reportId, Pageable pageable);

    // 실행 로그(ReconciliationBatchExecutionLogger)가 타입별 정확한 건수를 찍기 위한 집계.
    // 상위 N건만으로 집계하면 500건을 넘는 순간부터 실제 분포와 어긋나므로, 별도 GROUP BY로 뺐다.
    @Query("""
            SELECT v.errorType, COUNT(v) FROM VerificationDetail v
             WHERE v.report.reportId = :reportId
             GROUP BY v.errorType
            """)
    List<Object[]> countGroupedByErrorType(@Param("reportId") Long reportId);
}
