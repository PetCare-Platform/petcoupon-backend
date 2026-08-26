package com.mycom.petcoupon.reconciliation.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

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
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
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
                .andExpect(jsonPath("$.result.errorCount").value(0));
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
