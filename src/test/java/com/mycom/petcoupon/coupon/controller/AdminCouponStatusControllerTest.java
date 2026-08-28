package com.mycom.petcoupon.coupon.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.mycom.petcoupon.coupon.dto.res.CouponFailureReasonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponLoadTestStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.service.CouponFailureReasonService;
import com.mycom.petcoupon.coupon.service.CouponLoadTestStatusService;
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
    private CouponLoadTestStatusService couponLoadTestStatusService;
    private CouponFailureReasonService couponFailureReasonService;
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
        CouponLoadTestStatusService couponLoadTestStatusService() {
            return mock(CouponLoadTestStatusService.class);
        }

        @Bean
        CouponFailureReasonService couponFailureReasonService() {
            return mock(CouponFailureReasonService.class);
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
                CouponRealtimeStatusService couponRealtimeStatusService,
                CouponLoadTestStatusService couponLoadTestStatusService,
                CouponFailureReasonService couponFailureReasonService
        ) {
            return new AdminCouponStatusController(
                    couponRealtimeStatusService, couponLoadTestStatusService, couponFailureReasonService
            );
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
        couponLoadTestStatusService = webContext.getBean(CouponLoadTestStatusService.class);
        couponFailureReasonService = webContext.getBean(CouponFailureReasonService.class);
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
    void getLoadTestStatusReturnsStatusWhenAdminTokenIsValid() throws Exception {
        CouponLoadTestStatusResponse response = CouponLoadTestStatusResponse.builder()
                .accepted(10)
                .passed(8)
                .rejected(2)
                .pending(0)
                .sent(0)
                .consumed(8)
                .failed(0)
                .dlq(1)
                .inProgressIdempotencyKeys(0)
                .overIssued(false)
                .duplicateUsers(0)
                .sequenceIntact(true)
                .elapsedSeconds(12)
                .build();
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponLoadTestStatusService.getLoadTestStatus(COUPON_ID)).thenReturn(response);

        mockMvc.perform(get("/admin/coupons/{couponId}/load-test-status", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.accepted").value(10))
                .andExpect(jsonPath("$.result.passed").value(8))
                .andExpect(jsonPath("$.result.rejected").value(2))
                .andExpect(jsonPath("$.result.dlq").value(1))
                .andExpect(jsonPath("$.result.overIssued").value(false))
                .andExpect(jsonPath("$.result.sequenceIntact").value(true))
                .andExpect(jsonPath("$.result.elapsedSeconds").value(12));

        verify(couponLoadTestStatusService).getLoadTestStatus(COUPON_ID);
    }

    @Test
    void getLoadTestStatusReturnsUnauthorizedWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/admin/coupons/{couponId}/load-test-status", COUPON_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401-0"));

        verifyNoInteractions(couponLoadTestStatusService);
    }

    @Test
    void getLoadTestStatusReturnsNotFoundWhenCouponDoesNotExist() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponLoadTestStatusService.getLoadTestStatus(COUPON_ID))
                .thenThrow(new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        mockMvc.perform(get("/admin/coupons/{couponId}/load-test-status", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON404-0"));
    }

    @Test
    void getFailureReasonsReturnsCountsWhenAdminTokenIsValid() throws Exception {
        CouponFailureReasonResponse response = CouponFailureReasonResponse.builder()
                .rejections(CouponFailureReasonResponse.Rejections.builder()
                        .soldOut(3)
                        .alreadyIssued(1)
                        .build())
                .failures(CouponFailureReasonResponse.Failures.builder()
                        .kafkaPublishFailed(2)
                        .consumeProcessingFailed(1)
                        .build())
                .build();
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponFailureReasonService.getFailureReasons(COUPON_ID)).thenReturn(response);

        mockMvc.perform(get("/admin/coupons/{couponId}/failure-reasons", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.rejections.soldOut").value(3))
                .andExpect(jsonPath("$.result.rejections.alreadyIssued").value(1))
                .andExpect(jsonPath("$.result.failures.kafkaPublishFailed").value(2))
                .andExpect(jsonPath("$.result.failures.consumeProcessingFailed").value(1));

        verify(couponFailureReasonService).getFailureReasons(COUPON_ID);
    }

    @Test
    void getFailureReasonsReturnsUnauthorizedWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/admin/coupons/{couponId}/failure-reasons", COUPON_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401-0"));

        verifyNoInteractions(couponFailureReasonService);
    }

    @Test
    void getFailureReasonsReturnsNotFoundWhenCouponDoesNotExist() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponFailureReasonService.getFailureReasons(COUPON_ID))
                .thenThrow(new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        mockMvc.perform(get("/admin/coupons/{couponId}/failure-reasons", COUPON_ID)
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON404-0"));
    }
}
