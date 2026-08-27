package com.mycom.petcoupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.mycom.petcoupon.coupon.dto.req.CouponFilterRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponListResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponPageResponse;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.service.CouponQueryService;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.global.auth.interceptor.AdminSessionInterceptor;
import com.mycom.petcoupon.global.auth.service.AdminSessionService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;
import com.mycom.petcoupon.global.config.WebConfig;

/**
 * standaloneSetup이 아니라 실제 웹 컨텍스트를 띄운다. 관리자 인증은 WebConfig가 등록하는
 * 인터셉터가 담당하는데, standaloneSetup으로는 인터셉터가 붙지 않아 401 케이스를 검증할 수 없다.
 * (AdminCouponStatusControllerTest와 같은 구성)
 */
class AdminCouponQueryControllerTest {

    private static final Long EVENT_ID = 1L;
    private static final String VALID_TOKEN = "valid-token";
    private static final CouponFilterRequest NO_FILTER = new CouponFilterRequest(null, null);
    private static final CouponPageRequest DEFAULT_PAGE = new CouponPageRequest(0, 20);

    private CouponQueryService couponQueryService;
    private AdminSessionService adminSessionService;
    private AnnotationConfigWebApplicationContext webContext;
    private MockMvc mockMvc;

    @Configuration
    @EnableWebMvc
    @Import(WebConfig.class)
    static class TestConfig {

        @Bean
        CouponQueryService couponQueryService() {
            return mock(CouponQueryService.class);
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
        AdminCouponQueryController adminCouponQueryController(CouponQueryService couponQueryService) {
            return new AdminCouponQueryController(couponQueryService);
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    @BeforeEach
    void setUp() {
        webContext = new AnnotationConfigWebApplicationContext();
        webContext.setServletContext(new MockServletContext());
        webContext.register(TestConfig.class);
        webContext.refresh();

        couponQueryService = webContext.getBean(CouponQueryService.class);
        adminSessionService = webContext.getBean(AdminSessionService.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    }

    @AfterEach
    void tearDown() {
        webContext.close();
    }

    @Test
    void getCouponsUsesDefaultPaginationWhenAdminTokenIsValid() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponQueryService.getCoupons(NO_FILTER, DEFAULT_PAGE)).thenReturn(pageResponse());

        mockMvc.perform(get("/admin/coupons")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andExpect(jsonPath("$.result.content[0].couponId").value(10L))
                .andExpect(jsonPath("$.result.content[0].eventId").value(EVENT_ID))
                .andExpect(jsonPath("$.result.content[0].eventName").value("여름 이벤트"))
                .andExpect(jsonPath("$.result.content[0].name").value("여름 정률 쿠폰"))
                .andExpect(jsonPath("$.result.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.result.content[0].totalQuantity").value(100))
                .andExpect(jsonPath("$.result.content[0].issuedQuantity").value(40))
                .andExpect(jsonPath("$.result.content[0].remainingQuantity").value(60))
                .andExpect(jsonPath("$.result.content[0].stockUpdatedAt").value("2026-08-21T10:00:00"))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(20))
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.totalPages").value(1))
                .andExpect(jsonPath("$.result.first").value(true))
                .andExpect(jsonPath("$.result.last").value(true));

        verify(couponQueryService).getCoupons(NO_FILTER, DEFAULT_PAGE);
    }

    @Test
    void getCouponsPassesFiltersAndPaginationToService() throws Exception {
        CouponFilterRequest filter = new CouponFilterRequest(EVENT_ID, CouponStatus.READY);
        CouponPageRequest pageRequest = new CouponPageRequest(2, 50);
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponQueryService.getCoupons(filter, pageRequest)).thenReturn(pageResponse());

        mockMvc.perform(get("/admin/coupons")
                        .param("eventId", String.valueOf(EVENT_ID))
                        .param("status", "READY")
                        .param("page", "2")
                        .param("size", "50")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk());

        verify(couponQueryService).getCoupons(filter, pageRequest);
    }

    @Test
    void getCouponsReturnsUnauthorizedWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/admin/coupons"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401-0"));

        verifyNoInteractions(couponQueryService);
    }

    @Test
    void getCouponsReturnsUnauthorizedWhenAdminTokenIsInvalid() throws Exception {
        when(adminSessionService.isValid("expired-token")).thenReturn(false);

        mockMvc.perform(get("/admin/coupons")
                        .header(AdminSessionInterceptor.HEADER, "expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401-0"));

        verifyNoInteractions(couponQueryService);
    }

    @Test
    void getCouponsRejectsUnsupportedPageSize() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons")
                        .param("size", "25")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON400-11"));

        verifyNoInteractions(couponQueryService);
    }

    @Test
    void getCouponsRejectsNegativePage() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons")
                        .param("page", "-1")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON400-11"));

        verifyNoInteractions(couponQueryService);
    }

    @Test
    void getCouponsRejectsNonNumericPage() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons")
                        .param("page", "first")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON400-11"));

        verifyNoInteractions(couponQueryService);
    }

    // 파라미터를 String으로 받는 이유. enum으로 받았다면 바인딩 단계에서 터져 500으로 나갔다.
    @Test
    void getCouponsRejectsUnknownStatus() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons")
                        .param("status", "UNKNOWN")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON400-12"));

        verifyNoInteractions(couponQueryService);
    }

    @Test
    void getCouponsRejectsNonNumericEventId() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons")
                        .param("eventId", "abc")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON400-12"));

        verifyNoInteractions(couponQueryService);
    }

    @Test
    void getCouponsRejectsNonPositiveEventId() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

        mockMvc.perform(get("/admin/coupons")
                        .param("eventId", "0")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON400-12"));

        verifyNoInteractions(couponQueryService);
    }

    // 존재하지 않는 이벤트로 필터한 경우 — 빈 목록이 아니라 404로 알린다.
    @Test
    void getCouponsReturnsNotFoundWhenEventDoesNotExist() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponQueryService.getCoupons(eq(new CouponFilterRequest(999L, null)), any(CouponPageRequest.class)))
                .thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

        mockMvc.perform(get("/admin/coupons")
                        .param("eventId", "999")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("EVENT404-0"))
                .andExpect(jsonPath("$.message").value("존재하지 않는 이벤트입니다."))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void getCouponsReturnsCouponErrorResponseWhenListQueryFails() throws Exception {
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponQueryService.getCoupons(NO_FILTER, DEFAULT_PAGE))
                .thenThrow(new GeneralException(CouponErrorCode.COUPON_LIST_QUERY_FAILED));

        mockMvc.perform(get("/admin/coupons")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON500-1"))
                .andExpect(jsonPath("$.message").value("쿠폰 목록 조회에 실패했습니다."))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void getCouponsReturnsEmptyContentWhenNoCouponMatches() throws Exception {
        CouponFilterRequest filter = new CouponFilterRequest(null, CouponStatus.SOLD_OUT);
        when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);
        when(couponQueryService.getCoupons(filter, DEFAULT_PAGE))
                .thenReturn(new CouponPageResponse(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/admin/coupons")
                        .param("status", "SOLD_OUT")
                        .header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.content").isEmpty())
                .andExpect(jsonPath("$.result.totalElements").value(0));
    }

    private CouponPageResponse pageResponse() {
        CouponListResponse coupon = CouponListResponse.builder()
                .couponId(10L)
                .eventId(EVENT_ID)
                .eventName("여름 이벤트")
                .name("여름 정률 쿠폰")
                .discountType(DiscountType.RATE)
                .discountValue(20)
                .minOrderAmount(30_000)
                .maxDiscountAmount(10_000)
                .issueStartAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .issueEndAt(LocalDateTime.of(2026, 8, 30, 23, 59))
                .validDays(7)
                .status(CouponStatus.ACTIVE)
                .totalQuantity(100)
                .issuedQuantity(40)
                .remainingQuantity(60)
                .stockUpdatedAt(LocalDateTime.of(2026, 8, 21, 10, 0))
                .build();

        return new CouponPageResponse(List.of(coupon), 0, 20, 1, 1, true, true);
    }
}
