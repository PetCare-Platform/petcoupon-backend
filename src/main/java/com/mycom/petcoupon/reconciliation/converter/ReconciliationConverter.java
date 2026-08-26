package com.mycom.petcoupon.reconciliation.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.reconciliation.dto.res.ReconciliationTriggerResponse;
import com.mycom.petcoupon.reconciliation.dto.res.VerificationDetailResponse;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;

@Component
public class ReconciliationConverter {

    // verification_detail은 리포트(=쿠폰) 하나당 최대 그 쿠폰의 전체 발급 건수만큼 생길 수 있다
    // (이 프로젝트 실측 최대 규모는 쿠폰당 5만 건 — load-test-scenario.md §3 기준). 정상 케이스면
    // 불일치가 몇 건 안 되지만, 뭔가 시스템적으로 잘못돼서 대량으로 나는 극단적 케이스에서 응답
    // JSON이 통째로 거대해지는 걸 막기 위해 응답에 담는 개수에 상한을 둔다. 실제 전체 건수는
    // verificationDetailCount로 확인할 수 있다 — 응답 계약을 안 깨는 추가 필드다.
    public static final int MAX_DETAILS_IN_RESPONSE = 500;

    public ReconciliationTriggerResponse toTriggerResponse(ReconciliationReport report) {
        List<VerificationDetail> details = report.getVerificationDetails();

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
                .verificationDetailCount(details.size())
                .verificationDetails(details.stream()
                        .limit(MAX_DETAILS_IN_RESPONSE)
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
