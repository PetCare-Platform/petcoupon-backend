package com.mycom.petcoupon.dashboard.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.dashboard.dto.res.CouponIssueStatusDistributionResponse;
import com.mycom.petcoupon.dashboard.dto.res.DashboardSummaryResponse;
import com.mycom.petcoupon.dashboard.service.DashboardSummaryService;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class DashboardSummaryControllerTest {

    @Mock
    private DashboardSummaryService dashboardSummaryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DashboardSummaryController(dashboardSummaryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getSummaryReturnsAggregatedFields() throws Exception {
        DashboardSummaryResponse response = DashboardSummaryResponse.builder()
                .totalEvents(10).activeEvents(4)
                .totalCoupons(20).activeCoupons(7)
                .startedCouponTotalStock(1000).startedCouponIssuedStock(300).startedCouponRemainingStock(700)
                .startedCouponIssueRate(0.3)
                .couponIssueStatusDistribution(List.of(
                        CouponIssueStatusDistributionResponse.builder()
                                .status(IssueStatus.ISSUED).count(300).build()
                ))
                .build();

        when(dashboardSummaryService.getSummary()).thenReturn(response);

        mockMvc.perform(get("/admin/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.totalEvents").value(10))
                .andExpect(jsonPath("$.result.activeEvents").value(4))
                .andExpect(jsonPath("$.result.totalCoupons").value(20))
                .andExpect(jsonPath("$.result.activeCoupons").value(7))
                .andExpect(jsonPath("$.result.startedCouponTotalStock").value(1000))
                .andExpect(jsonPath("$.result.startedCouponIssuedStock").value(300))
                .andExpect(jsonPath("$.result.startedCouponRemainingStock").value(700))
                .andExpect(jsonPath("$.result.startedCouponIssueRate").value(0.3))
                .andExpect(jsonPath("$.result.couponIssueStatusDistribution[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.result.couponIssueStatusDistribution[0].count").value(300));
    }
}
