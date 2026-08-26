package com.mycom.petcoupon.reconciliation.repository;

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
}
