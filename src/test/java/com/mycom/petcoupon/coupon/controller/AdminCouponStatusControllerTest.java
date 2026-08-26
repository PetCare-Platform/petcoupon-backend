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

import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
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
}
