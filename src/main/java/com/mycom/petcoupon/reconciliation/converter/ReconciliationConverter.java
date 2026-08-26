package com.mycom.petcoupon.reconciliation.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.reconciliation.dto.res.ReconciliationTriggerResponse;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;

@Component
public class ReconciliationConverter {

    public ReconciliationTriggerResponse toTriggerResponse(ReconciliationReport report) {
        return ReconciliationTriggerResponse.builder()
                .reportId(report.getReportId())
                .couponId(report.getCoupon().getCouponId())
                .asOfAt(report.getAsOfAt())
                .result(report.getResult())
                .totalCount(report.getTotalCount())
                .successCount(report.getSuccessCount())
                .errorCount(report.getErrorCount())
                .build();
    }
}
