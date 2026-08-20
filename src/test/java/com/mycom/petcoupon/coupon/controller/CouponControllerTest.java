package com.mycom.petcoupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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

import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.service.CouponIssueService;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

/**
 * PetCouponApplication에 붙은 @EnableJpaAuditing 때문에 @WebMvcTest가 JPA까지 끌고 들어와 실패한다.
 * 그래서 Spring Boot 컨텍스트 없이 순수 Spring MVC(@EnableWebMvc)로만 최소 컨텍스트를 띄운다.
 * MethodValidationPostProcessor는 운영에서 ValidationAutoConfiguration이 등록해주는 것과 동일한 빈이라,
 * @PathVariable @Positive 검증이 실제 서버와 동일하게 ConstraintViolationException 경로로 동작한다.
 */
class CouponControllerTest {

    private CouponIssueService couponIssueService;
    private MockMvc mockMvc;

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        CouponIssueService couponIssueService() {
            return mock(CouponIssueService.class);
        }

        @Bean
        CouponController couponController(CouponIssueService service) {
            return new CouponController(service);
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

        couponIssueService = webCtx.getBean(CouponIssueService.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webCtx).build();
    }

    @Test
    void couponId가_0이면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/coupons/{couponId}/issues", 0L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void couponId가_음수면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/coupons/{couponId}/issues", -1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void 정상_요청이면_200과_응답값을_반환한다() throws Exception {
        when(couponIssueService.issue(eq(5L), any()))
                .thenReturn(CouponIssueCreateResponse.builder()
                        .couponId(5L)
                        .userId(1L)
                        .build());

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.couponId").value(5))
                .andExpect(jsonPath("$.result.userId").value(1));
    }
}