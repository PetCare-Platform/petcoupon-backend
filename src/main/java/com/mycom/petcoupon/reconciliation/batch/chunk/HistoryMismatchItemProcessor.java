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
 * HistoryMismatchRow 한 건을 VerificationDetail로 바꾼다. report 연관관계는
 * entityManager.getReference()로 얻은 프록시로 채운다 — assignReport()(부모의 지연로딩
 * 컬렉션에 append)를 쓰면 청크가 쌓일수록 그 컬렉션 전체를 메모리로 끌어오게 된다.
 */
@Component
@StepScope
public class HistoryMismatchItemProcessor implements ItemProcessor<HistoryMismatchRow, VerificationDetail> {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("#{jobExecutionContext['reportId']}")
    private Long reportId;

    @Override
    public VerificationDetail process(HistoryMismatchRow row) {
        ReconciliationReport reportRef = entityManager.getReference(ReconciliationReport.class, reportId);

        return VerificationDetail.builder()
                .report(reportRef)
                .errorType(VerificationErrorType.HISTORY_MISMATCH)
                .couponIssueId(row.couponIssueId())
                .userId(row.userId())
                .expectedValue(row.toStatus() == null ? "이력 없음" : row.toStatus())
                .actualValue(row.status())
                .message(row.toStatus() == null ? "발급 이력이 없습니다" : "현재 상태와 최종 이력의 to_status가 다릅니다")
                .build();
    }
}
