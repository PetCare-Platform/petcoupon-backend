package com.mycom.petcoupon.coupon.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.mycom.petcoupon.coupon.dto.res.CouponPipelineDrainStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.service.CouponRealtimeStatusService;
import com.mycom.petcoupon.global.auth.interceptor.AdminSessionInterceptor;
import com.mycom.petcoupon.global.auth.service.AdminSessionService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;
import com.mycom.petcoupon.global.config.WebConfig;

class AdminCouponStatusControllerTest {

    private static final Long COUPON_ID = 5L;
    private static final String VALID_TOKEN = "valid-token";

    private CouponRealtimeStatusService couponRealtimeStatusService;
    private AdminSessionService adminSessionService;
    private AnnotationConfigWebApplicationContext webContext;
    private MockMvc mockMvc;

    @Configuration
    @EnableWebMvc
    @Import(WebConfig.class)
    static class TestConfig {

        @Bean
        CouponRealtimeStatusService couponRealtimeStatusService() {
            return mock(CouponRealtimeStatusService.class);
        }

        @Bean
        AdminSessionService adminSessionService() {
            return mock(AdminSessionService.class);
        }

        @Bean
        AdminSessionInterceptor adminSessionInterceptor(AdminSessionService adminSessionService) {
            return new AdminSessionInterceptor(adminSessionService);
        }

        @Bean
        AdminCouponStatusController adminCouponStatusController(
                CouponRealtimeStatusService couponRealtimeStatusService
        ) {
            return new AdminCouponStatusController(couponRealtimeStatusService);
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }

        @Bean
        MethodValidationPostProcessor methodValidationPostProcessor(LocalValidatorFactoryBean validator) {
            MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
            processor.setValidator(validator);
            return processor;
        }
    }

    @BeforeEach
    void setUp() {
        webContext = new AnnotationConfigWebApplicationContext();
        webContext.setServletContext(new MockServletContext());
        webContext.register(TestConfig.class);
        webContext.refresh();

        couponRealtimeStatusService = webContext.getBean(CouponRealtimeStatusService.class);
        adminSessionService = webContext.getBean(AdminSessionService.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    }

    @AfterEach
    void tearDown() {
        webContext.close();
    }

    @Test
    void getRealtimeStatusReturnsCouponStatusWhenAdminTokenIsValid() throws Exception {
        CouponRealtimeStatusResponse response = CouponRealtimeStatusResponse.builder()
                .couponId(COUPON_ID)
                .totalQuantity(100)
                .remainingQuantity(60)
                .issuedQuantity(40)
                .initialized(true)
                .build();
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getRealtimeStatus(COUPON_ID)).thenReturn(response);

        mockMvc.perform(get("/admin/coupons/{couponId}/status", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.result.totalQuantity").value(100))
                .andExpect(jsonPath("$.result.remainingQuantity").value(60))
                .andExpect(jsonPath("$.result.issuedQuantity").value(40))
                .andExpect(jsonPath("$.result.initialized").value(true));

        verify(couponRealtimeStatusService).getRealtimeStatus(COUPON_ID);
    }

    @Test
    void getRealtimeStatusReturnsUnauthorizedWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/admin/coupons/{couponId}/status", COUPON_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401-0"));

        verifyNoInteractions(couponRealtimeStatusService);
    }

    @Test
    void getRealtimeStatusReturnsBadRequestWhenCouponIdIsNotPositive() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons/{couponId}/status", 0L)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON400-1"));

        verifyNoInteractions(couponRealtimeStatusService);
    }

    @Test
    void getRealtimeStatusReturnsNotFoundWhenCouponDoesNotExist() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getRealtimeStatus(COUPON_ID))
                .thenThrow(new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        mockMvc.perform(get("/admin/coupons/{couponId}/status", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON404-0"));
    }

    @Test
    void getRealtimeStatusReturnsServiceUnavailableWhenRedisReadFails() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getRealtimeStatus(COUPON_ID))
                .thenThrow(new GeneralException(CouponErrorCode.REALTIME_STOCK_READ_FAILED));

        mockMvc.perform(get("/admin/coupons/{couponId}/status", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON503-4"));
    }

    @Test
    void getPipelineDrainStatusReturnsDrainStatusWhenAdminTokenIsValid() throws Exception {
        CouponPipelineDrainStatusResponse response = CouponPipelineDrainStatusResponse.builder()
                .couponStatus(CouponStatus.ENDED)
                .outboxUnconsumed(0L)
                .streamUndelivered(0L)
                .streamActivePending(0L)
                .checkFailed(false)
                .build();
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getPipelineDrainStatus(COUPON_ID)).thenReturn(response);

        mockMvc.perform(get("/admin/coupons/{couponId}/pipeline-drain-status", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.couponStatus").value("ENDED"))
                .andExpect(jsonPath("$.result.outboxUnconsumed").value(0))
                .andExpect(jsonPath("$.result.streamUndelivered").value(0))
                .andExpect(jsonPath("$.result.streamActivePending").value(0))
                .andExpect(jsonPath("$.result.checkFailed").value(false));

        verify(couponRealtimeStatusService).getPipelineDrainStatus(COUPON_ID);
    }

    @Test
    void getPipelineDrainStatusReturnsDrainStatusWithCheckFailedTrueWhenRedisCheckFails() throws Exception {
        CouponPipelineDrainStatusResponse response = CouponPipelineDrainStatusResponse.builder()
                .couponStatus(CouponStatus.ACTIVE)
                .outboxUnconsumed(2L)
                .streamUndelivered(0L)
                .streamActivePending(0L)
                .checkFailed(true)
                .build();
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getPipelineDrainStatus(COUPON_ID)).thenReturn(response);

        mockMvc.perform(get("/admin/coupons/{couponId}/pipeline-drain-status", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.couponStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.result.outboxUnconsumed").value(2))
                .andExpect(jsonPath("$.result.streamUndelivered").value(0))
                .andExpect(jsonPath("$.result.streamActivePending").value(0))
                .andExpect(jsonPath("$.result.checkFailed").value(true));

        verify(couponRealtimeStatusService).getPipelineDrainStatus(COUPON_ID);
    }

    @Test
    void getPipelineDrainStatusReturnsUnauthorizedWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/admin/coupons/{couponId}/pipeline-drain-status", COUPON_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401-0"));

        verifyNoInteractions(couponRealtimeStatusService);
    }

    @Test
    void getPipelineDrainStatusReturnsNotFoundWhenCouponDoesNotExist() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getPipelineDrainStatus(COUPON_ID))
                .thenThrow(new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        mockMvc.perform(get("/admin/coupons/{couponId}/pipeline-drain-status", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON404-0"));
    }

    @Test
    void getIssueTimeSeriesReturnsTimeSeriesWhenAdminTokenIsValid() throws Exception {
        var timeSeriesResponse = com.mycom.petcoupon.coupon.dto.res.CouponIssueTimeSeriesResponse.builder()
                .couponId(COUPON_ID)
                .windowSeconds(90)
                .bucketSeconds(5)
                .timeSeries(List.of(
                        com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse.builder()
                                .bucket("2026-08-29 01:50:00")
                                .issuedCount(50L)
                                .failedCount(2L)
                                .inProgressCount(1L)
                                .build()
                ))
                .build();

        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getIssueTimeSeries(COUPON_ID, 90, 5)).thenReturn(timeSeriesResponse);

        mockMvc.perform(get("/admin/coupons/{couponId}/issue-timeseries", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN)
                        .param("windowSeconds", "90")
                        .param("bucketSeconds", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.result.windowSeconds").value(90))
                .andExpect(jsonPath("$.result.bucketSeconds").value(5))
                .andExpect(jsonPath("$.result.timeSeries[0].bucket").value("2026-08-29 01:50:00"))
                .andExpect(jsonPath("$.result.timeSeries[0].issuedCount").value(50))
                .andExpect(jsonPath("$.result.timeSeries[0].failedCount").value(2))
                .andExpect(jsonPath("$.result.timeSeries[0].inProgressCount").value(1));

        verify(couponRealtimeStatusService).getIssueTimeSeries(COUPON_ID, 90, 5);
    }

    @Test
    void getIssueTimeSeriesUsesDefaultParametersWhenNotProvided() throws Exception {
        var timeSeriesResponse = com.mycom.petcoupon.coupon.dto.res.CouponIssueTimeSeriesResponse.builder()
                .couponId(COUPON_ID)
                .windowSeconds(90)
                .bucketSeconds(5)
                .timeSeries(List.of())
                .build();

        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getIssueTimeSeries(COUPON_ID, 90, 5)).thenReturn(timeSeriesResponse);

        mockMvc.perform(get("/admin/coupons/{couponId}/issue-timeseries", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.windowSeconds").value(90))
                .andExpect(jsonPath("$.result.bucketSeconds").value(5));

        verify(couponRealtimeStatusService).getIssueTimeSeries(COUPON_ID, 90, 5);
    }

    @Test
    void getIssueTimeSeriesReturnsUnauthorizedWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/admin/coupons/{couponId}/issue-timeseries", COUPON_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401-0"));

        verifyNoInteractions(couponRealtimeStatusService);
    }

    @Test
    void getIssueTimeSeriesReturnsBadRequestWhenWindowSecondsIsZero() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons/{couponId}/issue-timeseries", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN)
                        .param("windowSeconds", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON400-1"));

        verifyNoInteractions(couponRealtimeStatusService);
    }

    @Test
    void getIssueTimeSeriesReturnsBadRequestWhenBucketSecondsExceedsMax() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons/{couponId}/issue-timeseries", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN)
                        .param("bucketSeconds", "301"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON400-1"));

        verifyNoInteractions(couponRealtimeStatusService);
    }

    @Test
    void getIssueTimeSeriesReturnsNotFoundWhenCouponDoesNotExist() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponRealtimeStatusService.getIssueTimeSeries(COUPON_ID, 90, 5))
                .thenThrow(new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        mockMvc.perform(get("/admin/coupons/{couponId}/issue-timeseries", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON404-0"));
    }
}
