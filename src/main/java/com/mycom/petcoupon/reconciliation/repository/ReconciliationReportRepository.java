package com.mycom.petcoupon.reconciliation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, Long> {
}
