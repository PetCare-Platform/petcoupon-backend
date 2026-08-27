package com.mycom.petcoupon.reconciliation.batch.tasklet;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.reconciliation.service.ReconciliationDetectionQueries;

import lombok.RequiredArgsConstructor;

/**
 * Step2 — 소량 검증 3종(DUPLICATE_ISSUE/STOCK_MISMATCH/SEQUENCE_GAP).
 * 청크 Step(HISTORY_MISMATCH/INVALID_STATUS/STOCK_NOT_RESTORED)보다 반드시 먼저 실행돼야
 * 한다 — assignReport()가 report.getVerificationDetails()에 append하는데, 이게 지연로딩
 * 컬렉션이라 청크 Step이 대량으로 써놓은 뒤에 호출하면 그 전체를 메모리로 끌어올린다.
 * 여기서는 컬렉션이 비어있는 시점에 호출해 그 문제를 피한다.
 *
 * STOCK_NOT_RESTORED는 원래 여기서 findStockNotRestored()로 함께 처리했으나, DLQ가 대량으로
 * 쌓이는 실제 장애 상황(300만 건 규모)에서는 getResultList()로 전체를 한 번에 읽는 이 방식
 * 자체가 OOM 위험이라 stockNotRestoredStep(청크)으로 옮겼다 — ReconciliationJobConfig 참고.
 */
@Component
@StepScope
@RequiredArgsConstructor
public class RemainingChecksTasklet implements Tasklet {

    private final CouponStockRepository couponStockRepository;
    private final ReconciliationReportRepository reconciliationReportRepository;
    private final ReconciliationDetectionQueries queries;

    @Value("#{jobParameters['couponId']}")
    private Long couponId;

    @Value("#{jobParameters['asOfAt']}")
    private LocalDateTime asOfAt;

    @Value("#{jobExecutionContext['reportId']}")
    private Long reportId;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ReconciliationReport report = reconciliationReportRepository.findById(reportId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));
        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        List<VerificationDetail> details = new ArrayList<>();
        details.addAll(queries.findDuplicateIssues(couponId, asOfAt));
        details.addAll(queries.findStockMismatch(stock, report.getRedisRemaining()));
        details.addAll(queries.findSequenceGap(couponId, report.getMaxSequenceNo(), asOfAt));

        details.forEach(d -> d.assignReport(report));

        return RepeatStatus.FINISHED;
    }
}
