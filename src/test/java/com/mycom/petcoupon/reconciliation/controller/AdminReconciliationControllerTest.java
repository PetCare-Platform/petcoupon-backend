package com.mycom.petcoupon.reconciliation.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;
import com.mycom.petcoupon.reconciliation.converter.ReconciliationConverter;
import com.mycom.petcoupon.reconciliation.dto.res.ReconciliationTriggerResponse;
import com.mycom.petcoupon.reconciliation.dto.res.VerificationDetailResponse;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.reconciliation.service.ReconciliationService;

@ExtendWith(MockitoExtension.class)
class AdminReconciliationControllerTest {

    private static final Long COUPON_ID = 1L;

    @Mock
    private ReconciliationService reconciliationService;

    @Mock
    private ReconciliationConverter reconciliationConverter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminReconciliationController(reconciliationService, reconciliationConverter))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void reconcileReturnsSuccessResponse() throws Exception {
        ReconciliationReport report = mock(ReconciliationReport.class);
        ReconciliationTriggerResponse response = ReconciliationTriggerResponse.builder()
                .reportId(10L)
                .couponId(COUPON_ID)
                .asOfAt(LocalDateTime.of(2026, 8, 25, 12, 0))
                .result(ReconciliationResult.MATCHED)
                .totalCount(100L)
                .successCount(100L)
                .errorCount(0L)
                .stockTotal(100)
                .stockIssued(100)
                .stockRemaining(0)
                .redisRemaining(0)
                .dbDlqCount(0L)
                .maxSequenceNo(100L)
                .verificationDetails(List.of())
                .build();

        when(reconciliationService.reconcile(COUPON_ID)).thenReturn(report);
        when(reconciliationConverter.toTriggerResponse(report)).thenReturn(response);

        mockMvc.perform(post("/admin/coupons/{couponId}/reconcile", COUPON_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.reportId").value(10L))
                .andExpect(jsonPath("$.result.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.result.result").value("MATCHED"))
                .andExpect(jsonPath("$.result.totalCount").value(100))
                .andExpect(jsonPath("$.result.errorCount").value(0))
                .andExpect(jsonPath("$.result.stockTotal").value(100))
                .andExpect(jsonPath("$.result.redisRemaining").value(0))
                .andExpect(jsonPath("$.result.maxSequenceNo").value(100))
                .andExpect(jsonPath("$.result.verificationDetails").isArray());
    }

    @Test
    void reconcileReturnsMismatchedResponseWithVerificationDetails() throws Exception {
        ReconciliationReport report = mock(ReconciliationReport.class);
        ReconciliationTriggerResponse response = ReconciliationTriggerResponse.builder()
                .reportId(11L)
                .couponId(COUPON_ID)
                .asOfAt(LocalDateTime.of(2026, 8, 26, 12, 0))
                .result(ReconciliationResult.MISMATCHED)
                .totalCount(0L)
                .successCount(0L)
                .errorCount(0L)
                .stockTotal(100)
                .stockIssued(90)
                .stockRemaining(10)
                .redisRemaining(5)
                .dbDlqCount(2L)
                .maxSequenceNo(90L)
                .verificationDetails(List.of(VerificationDetailResponse.builder()
                        .errorType(VerificationErrorType.STOCK_MISMATCH)
                        .expectedValue("10")
                        .actualValue("5")
                        .message("DB 재고와 Redis 재고가 일치하지 않습니다")
                        .build()))
                .build();

        when(reconciliationService.reconcile(COUPON_ID)).thenReturn(report);
        when(reconciliationConverter.toTriggerResponse(report)).thenReturn(response);

        mockMvc.perform(post("/admin/coupons/{couponId}/reconcile", COUPON_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.result").value("MISMATCHED"))
                .andExpect(jsonPath("$.result.verificationDetails[0].errorType").value("STOCK_MISMATCH"))
                .andExpect(jsonPath("$.result.verificationDetails[0].expectedValue").value("10"))
                .andExpect(jsonPath("$.result.verificationDetails[0].actualValue").value("5"));
    }

    @Test
    void reconcileReturnsCouponNotFound() throws Exception {
        when(reconciliationService.reconcile(COUPON_ID))
                .thenThrow(new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        mockMvc.perform(post("/admin/coupons/{couponId}/reconcile", COUPON_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON404-0"))
                .andExpect(jsonPath("$.message").value("존재하지 않는 쿠폰입니다."));
    }
}
