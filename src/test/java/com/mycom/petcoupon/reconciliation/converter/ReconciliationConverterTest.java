package com.mycom.petcoupon.reconciliation.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.reconciliation.batch.service.ReconciliationBatchResult;
import com.mycom.petcoupon.reconciliation.dto.res.ReconciliationTriggerResponse;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;

class ReconciliationConverterTest {

    private final ReconciliationConverter converter = new ReconciliationConverter();

    @Test
    void toTriggerResponse는_재고_필드와_검증상세를_모두_매핑한다() {
        Coupon coupon = mock(Coupon.class);
        when(coupon.getCouponId()).thenReturn(1L);

        VerificationDetail detail = VerificationDetail.builder()
                .errorType(VerificationErrorType.STOCK_MISMATCH)
                .expectedValue("10")
                .actualValue("5")
                .message("DB 재고와 Redis 재고가 일치하지 않습니다")
                .build();

        ReconciliationReport report = mock(ReconciliationReport.class);
        when(report.getReportId()).thenReturn(10L);
        when(report.getCoupon()).thenReturn(coupon);
        when(report.getAsOfAt()).thenReturn(LocalDateTime.of(2026, 8, 26, 12, 0));
        when(report.getResult()).thenReturn(ReconciliationResult.MISMATCHED);
        when(report.getTotalCount()).thenReturn(0L);
        when(report.getSuccessCount()).thenReturn(0L);
        when(report.getErrorCount()).thenReturn(0L);
        when(report.getStockTotal()).thenReturn(100);
        when(report.getStockIssued()).thenReturn(90);
        when(report.getStockRemaining()).thenReturn(10);
        when(report.getRedisRemaining()).thenReturn(5);
        when(report.getDbDlqCount()).thenReturn(2L);
        when(report.getMaxSequenceNo()).thenReturn(90L);

        ReconciliationBatchResult result = new ReconciliationBatchResult(
                report, 1L, List.of(detail), Map.of(VerificationErrorType.STOCK_MISMATCH, 1L));

        ReconciliationTriggerResponse response = converter.toTriggerResponse(result);

        assertThat(response.reportId()).isEqualTo(10L);
        assertThat(response.couponId()).isEqualTo(1L);
        assertThat(response.stockTotal()).isEqualTo(100);
        assertThat(response.stockIssued()).isEqualTo(90);
        assertThat(response.stockRemaining()).isEqualTo(10);
        assertThat(response.redisRemaining()).isEqualTo(5);
        assertThat(response.dbDlqCount()).isEqualTo(2L);
        assertThat(response.maxSequenceNo()).isEqualTo(90L);

        assertThat(response.verificationDetailCount()).isEqualTo(1L);
        assertThat(response.verificationDetails()).hasSize(1);
        assertThat(response.verificationDetails().get(0).errorType()).isEqualTo(VerificationErrorType.STOCK_MISMATCH);
        assertThat(response.verificationDetails().get(0).expectedValue()).isEqualTo("10");
        assertThat(response.verificationDetails().get(0).actualValue()).isEqualTo("5");
    }

    @Test
    void toTriggerResponse는_재고_필드가_null이어도_그대로_통과시킨다() {
        Coupon coupon = mock(Coupon.class);
        when(coupon.getCouponId()).thenReturn(1L);

        // Mockito는 unstub된 Integer/Long 반환 메서드는 null이 아니라 0을 돌려주므로,
        // "값이 없으면 null이 그대로 전달되는지"를 보려면 null 리턴을 명시적으로 스텁해야 한다.
        ReconciliationReport report = mock(ReconciliationReport.class);
        when(report.getReportId()).thenReturn(10L);
        when(report.getCoupon()).thenReturn(coupon);
        when(report.getResult()).thenReturn(ReconciliationResult.MATCHED);
        when(report.getStockTotal()).thenReturn(null);
        when(report.getRedisRemaining()).thenReturn(null);
        when(report.getDbDlqCount()).thenReturn(null);

        ReconciliationBatchResult result = new ReconciliationBatchResult(report, 0L, List.of(), Map.of());

        ReconciliationTriggerResponse response = converter.toTriggerResponse(result);

        assertThat(response.stockTotal()).isNull();
        assertThat(response.redisRemaining()).isNull();
        assertThat(response.dbDlqCount()).isNull();
        assertThat(response.verificationDetails()).isEmpty();
    }

    // 응답이 조회 시점(Pageable)에서 이미 잘려온 것을 그대로 담는지 — 여기서 다시 자르지 않는지 확인.
    // MAX_DETAILS_IN_RESPONSE(500)보다 많은 항목을 넘겨도 toTriggerResponse가 손대지 않아야 한다.
    @Test
    void toTriggerResponse는_topVerificationDetails를_그대로_담고_다시_자르지_않는다() {
        Coupon coupon = mock(Coupon.class);
        when(coupon.getCouponId()).thenReturn(1L);

        ReconciliationReport report = mock(ReconciliationReport.class);
        when(report.getReportId()).thenReturn(10L);
        when(report.getCoupon()).thenReturn(coupon);
        when(report.getResult()).thenReturn(ReconciliationResult.MISMATCHED);

        List<VerificationDetail> moreThanCap = java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> VerificationDetail.builder()
                        .errorType(VerificationErrorType.STOCK_MISMATCH)
                        .build())
                .toList();

        ReconciliationBatchResult result = new ReconciliationBatchResult(report, 10_000L, moreThanCap, Map.of());

        ReconciliationTriggerResponse response = converter.toTriggerResponse(result);

        // 조회 쪽에서 자르는 책임이라, 여기서는 넘어온 걸 그대로(501건) 담는다 —
        // 실제 응답이 500건으로 잘리는 건 ReconciliationJobTriggerServiceTest의 Pageable 조회에서 보장한다.
        assertThat(response.verificationDetails()).hasSize(501);
        assertThat(response.verificationDetailCount()).isEqualTo(10_000L);
    }
}
