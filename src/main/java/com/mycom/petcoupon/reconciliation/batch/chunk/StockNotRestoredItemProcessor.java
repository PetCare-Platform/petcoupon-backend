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
 * StockNotRestoredRow 한 건을 VerificationDetail로 바꾼다. report 연관관계는
 * entityManager.getReference()로 얻은 프록시로 채운다 — assignReport()(부모의 지연로딩
 * 컬렉션에 append)를 쓰면 청크가 쌓일수록 그 컬렉션 전체를 메모리로 끌어오게 된다.
 */
@Component
@StepScope
public class StockNotRestoredItemProcessor implements ItemProcessor<StockNotRestoredRow, VerificationDetail> {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("#{jobExecutionContext['reportId']}")
    private Long reportId;

    @Override
    public VerificationDetail process(StockNotRestoredRow row) {
        ReconciliationReport reportRef = entityManager.getReference(ReconciliationReport.class, reportId);

        return VerificationDetail.builder()
                .report(reportRef)
                .errorType(VerificationErrorType.STOCK_NOT_RESTORED)
                .userId(row.userId())
                .expectedValue("재고 복구됨")
                .actualValue("DLQ 확정 (message_id=" + row.messageId() + ")")
                .message("최종 실패했지만 예약된 재고가 복구되지 않았습니다")
                .build();
    }
}
