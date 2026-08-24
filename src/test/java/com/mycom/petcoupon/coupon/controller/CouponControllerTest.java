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
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.service.CouponIssueService;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;
import com.mycom.petcoupon.idempotency.service.IdempotencyDecision;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * PetCouponApplication에 붙은 @EnableJpaAuditing 때문에 @WebMvcTest가 JPA까지 끌고 들어와 실패한다.
 * 그래서 Spring Boot 컨텍스트 없이 순수 Spring MVC(@EnableWebMvc)로만 최소 컨텍스트를 띄운다.
 * MethodValidationPostProcessor는 운영에서 ValidationAutoConfiguration이 등록해주는 것과 동일한 빈이라,
 * @PathVariable/@RequestHeader 제약이 실제 서버와 동일하게 동작한다.
 */
class CouponControllerTest {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String KEY = "test-key-1";

    private CouponIssueService couponIssueService;
    private IdempotencyKeyService idempotencyKeyService;
    private CouponRepository couponRepository;
    private AppUserRepository appUserRepository;
    private MockMvc mockMvc;

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        CouponIssueService couponIssueService() {
            return mock(CouponIssueService.class);
        }

        @Bean
        IdempotencyKeyService idempotencyKeyService() {
            return mock(IdempotencyKeyService.class);
        }

        @Bean
        CouponRepository couponRepository() {
            return mock(CouponRepository.class);
        }

        @Bean
        AppUserRepository appUserRepository() {
            return mock(AppUserRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        CouponController couponController(
                CouponIssueService service,
                IdempotencyKeyService idempotencyKeyService,
                CouponRepository couponRepository,
                AppUserRepository appUserRepository,
                ObjectMapper objectMapper) {
            return new CouponController(service, idempotencyKeyService, couponRepository, appUserRepository, objectMapper);
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
        idempotencyKeyService = webCtx.getBean(IdempotencyKeyService.class);
        couponRepository = webCtx.getBean(CouponRepository.class);
        appUserRepository = webCtx.getBean(AppUserRepository.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webCtx).build();

        when(couponRepository.existsById(any())).thenReturn(true);
        when(appUserRepository.existsById(any())).thenReturn(true);
        when(idempotencyKeyService.begin(any(), any(), any())).thenReturn(IdempotencyDecision.proceed(1L));
    }

    @Test
    void couponId가_0이면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/coupons/{couponId}/issues", 0L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void couponId가_음수면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/coupons/{couponId}/issues", -1L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void Idempotency_Key_헤더가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void Idempotency_Key가_64자를_초과하면_400을_반환한다() throws Exception {
        String tooLong = "a".repeat(65);

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .header(IDEMPOTENCY_HEADER, tooLong)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400-1"));
    }

    @Test
    void 존재하지_않는_쿠폰이면_멱등성_레코드_생성_전에_404를_반환한다() throws Exception {
        when(couponRepository.existsById(5L)).thenReturn(false);

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON404-0"));

        org.mockito.Mockito.verifyNoInteractions(idempotencyKeyService);
    }

    @Test
    void 존재하지_않는_userId면_멱등성_레코드_생성_전에_404를_반환한다() throws Exception {
        when(appUserRepository.existsById(1L)).thenReturn(false);

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON404-0"));

        org.mockito.Mockito.verifyNoInteractions(idempotencyKeyService);
    }

    @Test
    void 처리중인_키로_재요청하면_409를_반환한다() throws Exception {
        when(idempotencyKeyService.begin(any(), any(), any())).thenReturn(IdempotencyDecision.conflict());

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON409-5"));
    }

    @Test
    void 완료된_키로_재요청하면_저장된_응답을_그대로_반환한다() throws Exception {
        when(idempotencyKeyService.begin(any(), any(), any()))
                .thenReturn(IdempotencyDecision.replay(200, "{\"isSuccess\":true,\"result\":{\"couponId\":5,\"userId\":1}}"));

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.couponId").value(5));
    }

    @Test
    void Idempotency_Key_대신_recordId_기반_requestId를_전달한다() throws Exception {
        when(idempotencyKeyService.begin(any(), any(), any())).thenReturn(IdempotencyDecision.proceed(42L));
        when(couponIssueService.issue(eq(5L), any(), any()))
                .thenReturn(CouponIssueCreateResponse.builder().couponId(5L).userId(1L).build());

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(couponIssueService).issue(eq(5L), any(), eq("issue:42"));
    }

    @Test
    void 정상_요청이면_200과_응답값을_반환하고_성공을_기록한다() throws Exception {
        when(couponIssueService.issue(eq(5L), any(), any()))
                .thenReturn(CouponIssueCreateResponse.builder()
                        .couponId(5L)
                        .userId(1L)
                        .build());

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.couponId").value(5))
                .andExpect(jsonPath("$.result.userId").value(1));

        org.mockito.Mockito.verify(idempotencyKeyService).succeed(eq(1L), eq(200), any());
    }

    @Test
    void Redis_예외가_터지면_바디없이_FAILED로_기록하고_500을_반환한다() throws Exception {
        when(couponIssueService.issue(eq(5L), any(), any())).thenThrow(new RuntimeException("redis down"));

        mockMvc.perform(post("/coupons/{couponId}/issues", 5L)
                        .header(IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isInternalServerError());

        org.mockito.Mockito.verify(idempotencyKeyService).failWithoutBody(1L);
    }
}
