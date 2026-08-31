package com.mycom.petcoupon.reconciliation.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, Long> {

    // verificationDetails는 findById로 온 report에서 절대 읽지 않는다(지연로딩 컬렉션이라
    // 트랜잭션 밖에서 접근하면 LazyInitializationException). 전체 건수·상위 N건·타입별 집계는
    // VerificationDetailRepository의 전용 쿼리로 필요한 만큼만 따로 가져온다 — ReconciliationBatchResult 참고.

    // 이력 목록 조회용(#154) — 트리거 응답(ReconciliationTriggerResponse)은 그 순간에만 볼 수 있고
    // 나중에 다시 조회할 방법이 없었다. asOfAt 기준 최신순으로 최근 N건만 가져온다 — 그래프/대시보드가
    // 필요한 만큼만(Pageable) 요청하게 해서 리포트가 계속 쌓여도 전체를 긁지 않는다.
    List<ReconciliationReport> findByCoupon_CouponIdOrderByAsOfAtDesc(Long couponId, Pageable pageable);
}
