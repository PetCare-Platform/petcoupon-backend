package com.mycom.petcoupon.reconciliation.batch.tasklet;

import java.time.LocalDateTime;

import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.reconciliation.repository.VerificationDetailRepository;

import lombok.RequiredArgsConstructor;

/**
 * Step5(마지막) — 모든 검증 Step이 쓴 VerificationDetail을 COUNT 쿼리로만 집계해서
 * (컬렉션을 메모리로 안 끌어옴 — 300만 건 규모에서도 안전) errorCount/successCount/result를
 * 확정하고 ReportInitTasklet이 임시값으로 넣어둔 report row를 UPDATE한다.
 */
@Component
@StepScope
@RequiredArgsConstructor
public class FinalizeReportTasklet implements Tasklet {

    private final ReconciliationReportRepository reconciliationReportRepository;
    private final VerificationDetailRepository verificationDetailRepository;

    @Value("#{jobExecutionContext['reportId']}")
    private Long reportId;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ReconciliationReport report = reconciliationReportRepository.findById(reportId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        long errorCount = verificationDetailRepository.countDistinctCouponIssueId(reportId);
        long totalDetailCount = verificationDetailRepository.countByReport_ReportId(reportId);
        long successCount = report.getTotalCount() - errorCount;
        ReconciliationResult result = totalDetailCount == 0
                ? ReconciliationResult.MATCHED
                : ReconciliationResult.MISMATCHED;

        report.finalizeCounts(successCount, errorCount, result, LocalDateTime.now());

        return RepeatStatus.FINISHED;
    }
}
