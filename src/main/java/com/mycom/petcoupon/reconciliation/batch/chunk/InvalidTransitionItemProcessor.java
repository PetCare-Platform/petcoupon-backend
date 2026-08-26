package com.mycom.petcoupon.reconciliation.batch.chunk;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * InvalidTransitionRow 한 건을 VerificationDetail로 바꾼다.
 * report 참조 방식은 HistoryMismatchItemProcessor와 동일한 이유로 getReference()를 쓴다.
 */
@Component
@StepScope
public class InvalidTransitionItemProcessor implements ItemProcessor<InvalidTransitionRow, VerificationDetail> {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("#{jobExecutionContext['reportId']}")
    private Long reportId;

    @Override
    public VerificationDetail process(InvalidTransitionRow row) {
        ReconciliationReport reportRef = entityManager.getReference(ReconciliationReport.class, reportId);

        return VerificationDetail.builder()
                .report(reportRef)
                .errorType(VerificationErrorType.INVALID_STATUS)
                .couponIssueId(row.couponIssueId())
                .userId(row.userId())
                .expectedValue("허용된 전이 목록 내 값")
                .actualValue(row.fromStatus() + " -> " + row.toStatus())
                .message("허용되지 않은 상태 전이입니다")
                .build();
    }
}
