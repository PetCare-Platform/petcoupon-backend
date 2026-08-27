package com.mycom.petcoupon.reconciliation.batch.tasklet;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.reconciliation.service.ReconciliationDetectionQueries;

import lombok.RequiredArgsConstructor;

/**
 * Step1 — 재고/Redis/DLQ/시퀀스 집계값을 계산하고 ReconciliationReport row를 먼저 INSERT한다.
 * 뒤이은 청크 Step들이 report_id(FK, NOT NULL)를 참조해야 해서 report row가 먼저 있어야 한다.
 *
 * errorCount/successCount/result는 모든 검증 Step이 끝나야 확정되는 값이라 여기서는 임시값을
 * 넣고, 생성된 reportId를 JobExecutionContext에 저장해 이후 Step들이 꺼내 쓰게 한다.
 * 최종 확정은 FinalizeReportTasklet(Step5)이 담당한다.
 */
@Component
@StepScope
@RequiredArgsConstructor
public class ReportInitTasklet implements Tasklet {

    private final CouponRepository couponRepository;
    private final CouponStockRepository couponStockRepository;
    private final ReconciliationReportRepository reconciliationReportRepository;
    private final ReconciliationDetectionQueries queries;

    @Value("#{jobParameters['couponId']}")
    private Long couponId;

    // 트리거 시각으로 고정된 기준 시각. 재현성(같은 asOfAt으로 재실행 시 같은 결과)을 위해
    // LocalDateTime.now()가 아니라 JobParameters의 값을 그대로 쓴다 — Job 실행 도중 시각이
    // 흘러도 모든 Step의 모든 쿼리가 이 값 하나로 "그 시점" 기준을 통일한다.
    @Value("#{jobParameters['asOfAt']}")
    private LocalDateTime asOfAt;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Coupon coupon = couponRepository.getReferenceById(couponId);
        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        LocalDateTime startedAt = LocalDateTime.now();

        Integer redisRemaining = queries.readRedisStock(couponId);
        long dbDlqCount = queries.countDlqMessages(couponId, asOfAt);
        Long maxSequenceNo = queries.findMaxSequenceNo(couponId, asOfAt);
        long totalCount = queries.countTotalIssues(couponId, asOfAt);

        Map<String, Long> statusCounts = queries.countByStatus(couponId, asOfAt);
        long dbActiveCount = statusCounts.getOrDefault("ISSUED", 0L) + statusCounts.getOrDefault("USED", 0L);
        long dbExpiredCount = statusCounts.getOrDefault("EXPIRED", 0L);

        ReconciliationReport report = ReconciliationReport.builder()
                .coupon(coupon)
                .asOfAt(asOfAt)
                .startedAt(startedAt)
                .finishedAt(null)
                .totalCount(totalCount)
                .successCount(totalCount) // 임시값 — FinalizeReportTasklet에서 확정
                .errorCount(0) // 임시값 — FinalizeReportTasklet에서 확정
                .stockTotal(stock.getTotalQuantity())
                .stockIssued(stock.getIssuedQuantity())
                .stockRemaining(stock.getRemainingQuantity())
                .dbActiveCount(dbActiveCount)
                .dbExpiredCount(dbExpiredCount)
                .dbDlqCount(dbDlqCount)
                .maxSequenceNo(maxSequenceNo)
                .redisRemaining(redisRemaining)
                // 임시값 — FinalizeReportTasklet에서 확정. MATCHED가 아니라 ERROR로 넣어둔다:
                // 이후 Step이 실패해 Job이 FAILED로 끝나면 이 row는 FinalizeReportTasklet을
                // 못 만나 영원히 이 임시값 그대로 남는데, MATCHED였다면 "검증했고 문제없음"으로
                // 오인된다. ERROR로 시작해두면 검증이 끝까지 못 간 상태라는 게 그대로 드러난다.
                .result(ReconciliationResult.ERROR)
                .build();

        ReconciliationReport saved = reconciliationReportRepository.save(report);

        ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        jobContext.putLong("reportId", saved.getReportId());

        return RepeatStatus.FINISHED;
    }
}
