package com.mycom.petcoupon.reconciliation.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.reconciliation.dto.res.ReconciliationTriggerResponse;
import com.mycom.petcoupon.reconciliation.dto.res.VerificationDetailResponse;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;

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
                .stockTotal(report.getStockTotal())
                .stockIssued(report.getStockIssued())
                .stockRemaining(report.getStockRemaining())
                .redisRemaining(report.getRedisRemaining())
                .dbDlqCount(report.getDbDlqCount())
                .maxSequenceNo(report.getMaxSequenceNo())
                .verificationDetails(report.getVerificationDetails().stream()
                        .map(this::toDetailResponse)
                        .toList())
                .build();
    }

    private VerificationDetailResponse toDetailResponse(VerificationDetail detail) {
        return VerificationDetailResponse.builder()
                .errorType(detail.getErrorType())
                .couponIssueId(detail.getCouponIssueId())
                .userId(detail.getUserId())
                .expectedValue(detail.getExpectedValue())
                .actualValue(detail.getActualValue())
                .message(detail.getMessage())
                .build();
    }
}
