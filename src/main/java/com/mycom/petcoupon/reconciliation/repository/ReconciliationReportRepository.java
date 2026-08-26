package com.mycom.petcoupon.reconciliation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, Long> {

    // verificationDetails는 findById로 온 report에서 절대 읽지 않는다(지연로딩 컬렉션이라
    // 트랜잭션 밖에서 접근하면 LazyInitializationException). 전체 건수·상위 N건·타입별 집계는
    // VerificationDetailRepository의 전용 쿼리로 필요한 만큼만 따로 가져온다 — ReconciliationBatchResult 참고.
}
