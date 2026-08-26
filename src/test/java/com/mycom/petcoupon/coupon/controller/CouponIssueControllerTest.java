package com.mycom.petcoupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.service.CouponIssueCancelService;
import com.mycom.petcoupon.coupon.service.CouponIssueQueryService;
import com.mycom.petcoupon.coupon.service.CouponIssueUseService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyStatusResult;

/**
 * PetCouponApplication에 붙은 @EnableJpaAuditing 때문에 @WebMvcTest가 JPA까지 끌고 들어와 실패한다.
 * CouponControllerTest와 동일하게 Spring Boot 컨텍스트 없이 순수 Spring MVC(@EnableWebMvc)로만 최소 컨텍스트를 띄운다.
 */
class CouponIssueControllerTest {

    private CouponIssueQueryService couponIssueQueryService;
    private CouponIssueUseService couponIssueUseService;
    private CouponIssueCancelService couponIssueCancelService;
    private MockMvc mockMvc;

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        CouponIssueQueryService couponIssueQueryService() {
            return mock(CouponIssueQueryService.class);
        }

        @Bean
        CouponIssueUseService couponIssueUseService() {
            return mock(CouponIssueUseService.class);
        }

        @Bean
        CouponIssueCancelService couponIssueCancelService() {
            return mock(CouponIssueCancelService.class);
        }

        @Bean
        CouponIssueController couponIssueController(
                CouponIssueQueryService queryService,
                CouponIssueUseService useService,
                CouponIssueCancelService cancelService) {
            return new CouponIssueController(queryService, useService, cancelService);
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
        AnnotationConfigWebApplicationContext webCtx = new AnnotationConfigWebApplicationContext();
        webCtx.setServletContext(new MockServletContext());
        webCtx.register(TestConfig.class);
        webCtx.refresh();

        couponIssueQueryService = webCtx.getBean(CouponIssueQueryService.class);
        couponIssueUseService = webCtx.getBean(CouponIssueUseService.class);
        couponIssueCancelService = webCtx.getBean(CouponIssueCancelService.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webCtx).build();
    }

    @Test
    void 정상_요청이면_200을_반환하고_서비스를_호출한다() throws Exception {
        mockMvc.perform(post("/coupon-issues/{couponIssueId}/cancel", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true));

        verify(couponIssueCancelService).cancelUsage(1L, 100L);
    }

    @Test
    void couponIssueId가_0이면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/coupon-issues/{couponIssueId}/cancel", 0L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void couponIssueId가_음수면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/coupon-issues/{couponIssueId}/cancel", -1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void userId가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/coupon-issues/{couponIssueId}/cancel", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void 존재하지_않는_발급건이면_404를_반환한다() throws Exception {
        doThrow(new GeneralException(CouponErrorCode.COUPON_ISSUE_NOT_FOUND))
                .when(couponIssueCancelService).cancelUsage(eq(1L), any());

        mockMvc.perform(post("/coupon-issues/{couponIssueId}/cancel", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":100}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON404-1"));
    }

    @Test
    void 본인의_쿠폰이_아니면_403을_반환한다() throws Exception {
        doThrow(new GeneralException(CouponErrorCode.NOT_COUPON_OWNER))
                .when(couponIssueCancelService).cancelUsage(eq(1L), any());

        mockMvc.perform(post("/coupon-issues/{couponIssueId}/cancel", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":999}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COUPON403-0"));
    }

    @Test
    void 사용중_상태가_아니면_409를_반환한다() throws Exception {
        doThrow(new GeneralException(CouponErrorCode.INVALID_ISSUE_STATUS))
                .when(couponIssueCancelService).cancelUsage(eq(1L), any());

        mockMvc.perform(post("/coupon-issues/{couponIssueId}/cancel", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON409-3"));
    }

    @Test
    void 처리중인_요청을_조회하면_IN_PROGRESS를_반환한다() throws Exception {
        when(couponIssueQueryService.getRequestStatus(1L, "key-in-progress"))
                .thenReturn(IdempotencyKeyStatusResult.inProgress());

        mockMvc.perform(get("/users/{userId}/coupon-issue-requests/status", 1L)
                        .queryParam("idempotencyKey", "key-in-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.status").value("IN_PROGRESS"));
    }

    @Test
    void 완료된_요청을_조회하면_저장된_응답을_그대로_반환한다() throws Exception {
        String storedBody = "{\"isSuccess\":true,\"code\":\"200\",\"message\":\"OK\",\"result\":{\"couponId\":1,\"userId\":100}}";
        when(couponIssueQueryService.getRequestStatus(1L, "key-done"))
                .thenReturn(IdempotencyKeyStatusResult.done(200, storedBody));

        mockMvc.perform(get("/users/{userId}/coupon-issue-requests/status", 1L)
                        .queryParam("idempotencyKey", "key-done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.couponId").value(1))
                .andExpect(jsonPath("$.result.userId").value(100));
    }

    @Test
    void 존재하지_않는_Idempotency_Key를_조회하면_404를_반환한다() throws Exception {
        when(couponIssueQueryService.getRequestStatus(1L, "key-unknown"))
                .thenReturn(IdempotencyKeyStatusResult.notFound());

        mockMvc.perform(get("/users/{userId}/coupon-issue-requests/status", 1L)
                        .queryParam("idempotencyKey", "key-unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON404-2"));
    }
}
