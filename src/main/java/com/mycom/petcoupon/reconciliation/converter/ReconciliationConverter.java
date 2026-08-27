package com.mycom.petcoupon.reconciliation.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.reconciliation.batch.service.ReconciliationBatchResult;
import com.mycom.petcoupon.reconciliation.dto.res.ReconciliationReportSummaryResponse;
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
    //
    // 이 상한은 여기서 자르는 게 아니라 조회 시점(ReconciliationJobTriggerService.loadResult가
    // Pageable로 넘기는 값)에 이미 적용된다 — 전체를 읽어서 버리지 않기 위함.
    public static final int MAX_DETAILS_IN_RESPONSE = 500;

    public ReconciliationTriggerResponse toTriggerResponse(ReconciliationBatchResult result) {
        ReconciliationReport report = result.report();

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
                .verificationDetailCount(result.verificationDetailCount())
                .verificationDetails(result.topVerificationDetails().stream()
                        .map(this::toDetailResponse)
                        .toList())
                .build();
    }

    // 이력 목록(#154)용 — verificationDetails 없이 요약 필드만 담아서 목록 N건을 가볍게 반환한다.
    public ReconciliationReportSummaryResponse toSummaryResponse(ReconciliationReport report) {
        return ReconciliationReportSummaryResponse.builder()
                .reportId(report.getReportId())
                .couponId(report.getCoupon().getCouponId())
                .asOfAt(report.getAsOfAt())
                .result(report.getResult())
                .totalCount(report.getTotalCount())
                .successCount(report.getSuccessCount())
                .errorCount(report.getErrorCount())
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
