package com.mycom.petcoupon.reconciliation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, Long> {

    // Batch Job은 report_id를 여러 Step(별도 트랜잭션)에 걸쳐 주고받다가, Job이 끝난 뒤
    // (트랜잭션 밖에서) 컨트롤러가 verificationDetails를 읽는다 — 그냥 findById로는 지연로딩
    // 컬렉션이라 LazyInitializationException이 난다. JOIN FETCH로 한 번에 채워서 반환한다.
    @Query("SELECT r FROM ReconciliationReport r LEFT JOIN FETCH r.verificationDetails WHERE r.reportId = :reportId")
    Optional<ReconciliationReport> findByIdWithDetails(@Param("reportId") Long reportId);
}
