package com.mycom.petcoupon.reconciliation.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.entity.Coupon;
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
        when(report.getVerificationDetails()).thenReturn(List.of(detail));

        ReconciliationTriggerResponse response = converter.toTriggerResponse(report);

        assertThat(response.reportId()).isEqualTo(10L);
        assertThat(response.couponId()).isEqualTo(1L);
        assertThat(response.stockTotal()).isEqualTo(100);
        assertThat(response.stockIssued()).isEqualTo(90);
        assertThat(response.stockRemaining()).isEqualTo(10);
        assertThat(response.redisRemaining()).isEqualTo(5);
        assertThat(response.dbDlqCount()).isEqualTo(2L);
        assertThat(response.maxSequenceNo()).isEqualTo(90L);

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
        when(report.getVerificationDetails()).thenReturn(List.of());

        ReconciliationTriggerResponse response = converter.toTriggerResponse(report);

        assertThat(response.stockTotal()).isNull();
        assertThat(response.redisRemaining()).isNull();
        assertThat(response.dbDlqCount()).isNull();
        assertThat(response.verificationDetails()).isEmpty();
    }
}
