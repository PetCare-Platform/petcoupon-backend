package com.mycom.petcoupon.coupon.controller;

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

import com.mycom.petcoupon.coupon.dto.res.IssueStatisticsResponse;
import com.mycom.petcoupon.coupon.dto.res.IssueStatusDistributionResponse;
import com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse;
import com.mycom.petcoupon.coupon.service.IssueStatisticsService;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;

@ExtendWith(MockitoExtension.class)
class IssueStatisticsControllerTest {

    @Mock
    private IssueStatisticsService issueStatisticsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IssueStatisticsController(issueStatisticsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getStatisticsReturnsTimeSeriesAndDistribution() throws Exception {
        IssueStatisticsResponse response = IssueStatisticsResponse.builder()
                .timeSeries(List.of(
                        IssueThroughputBucketResponse.builder()
                                .bucket("2026-08-27 10:00:00").issuedCount(12).failedCount(3).build()
                ))
                .distribution(List.of(
                        IssueStatusDistributionResponse.builder()
                                .status(IssueMessageStatus.CONSUMED).count(100).build(),
                        IssueStatusDistributionResponse.builder()
                                .status(IssueMessageStatus.DLQ).count(3).build()
                ))
                .build();

        when(issueStatisticsService.getStatistics()).thenReturn(response);

        mockMvc.perform(get("/admin/coupon-issue/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.timeSeries[0].bucket").value("2026-08-27 10:00:00"))
                .andExpect(jsonPath("$.result.timeSeries[0].issuedCount").value(12))
                .andExpect(jsonPath("$.result.timeSeries[0].failedCount").value(3))
                .andExpect(jsonPath("$.result.distribution[0].status").value("CONSUMED"))
                .andExpect(jsonPath("$.result.distribution[0].count").value(100))
                .andExpect(jsonPath("$.result.distribution[1].status").value("DLQ"))
                .andExpect(jsonPath("$.result.distribution[1].count").value(3));
    }
}
