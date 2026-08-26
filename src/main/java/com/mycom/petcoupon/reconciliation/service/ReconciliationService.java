package com.mycom.petcoupon.reconciliation.service;

import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;

public interface ReconciliationService {
    ReconciliationReport reconcile(Long couponId);
}
