package com.mycom.petcoupon.coupon.controller;

import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.service.CouponService;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminCouponControllerTest {

	private static final Long EVENT_ID = 1L;

	@Mock
	private CouponService couponService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new AdminCouponController(couponService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void createCouponReturnsCreatedResponse() throws Exception {
		CouponCreateRequest request = validRequest();
		CouponCreateResponse response = CouponCreateResponse.builder()
				.couponId(10L)
				.eventId(EVENT_ID)
				.name(request.name())
				.discountType(request.discountType())
				.discountValue(request.discountValue())
				.minOrderAmount(request.minOrderAmount())
				.maxDiscountAmount(request.maxDiscountAmount())
				.issueStartAt(request.issueStartAt())
				.issueEndAt(request.issueEndAt())
				.validDays(request.validDays())
				.totalQuantity(request.totalQuantity())
				.status(CouponStatus.READY)
				.build();
		when(couponService.createCoupon(EVENT_ID, request)).thenReturn(response);

		mockMvc.perform(post("/admin/events/{eventId}/coupons", EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(validRequestJson()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("201"))
				.andExpect(jsonPath("$.result.couponId").value(10L))
				.andExpect(jsonPath("$.result.eventId").value(EVENT_ID))
				.andExpect(jsonPath("$.result.name").value("여름 정률 쿠폰"))
				.andExpect(jsonPath("$.result.discountType").value("RATE"))
				.andExpect(jsonPath("$.result.status").value("READY"));
	}

	@Test
	void createCouponReturnsValidationErrorWhenNameIsBlank() throws Exception {
		mockMvc.perform(post("/admin/events/{eventId}/coupons", EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": " ",
							  "discountType": "RATE",
							  "discountValue": 20,
							  "minOrderAmount": 30000,
							  "maxDiscountAmount": 10000,
							  "issueStartAt": "2026-08-21T09:00:00",
							  "issueEndAt": "2026-08-30T23:59:00",
							  "validDays": 7,
							  "totalQuantity": 100
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.name").value("쿠폰 이름은 필수입니다."));

		verifyNoInteractions(couponService);
	}

	@Test
	void createCouponReturnsInvalidJsonWhenDiscountTypeIsUnknown() throws Exception {
		mockMvc.perform(post("/admin/events/{eventId}/coupons", EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "여름 쿠폰",
							  "discountType": "UNKNOWN",
							  "discountValue": 20,
							  "minOrderAmount": 30000,
							  "maxDiscountAmount": 10000,
							  "issueStartAt": "2026-08-21T09:00:00",
							  "issueEndAt": "2026-08-30T23:59:00",
							  "validDays": 7,
							  "totalQuantity": 100
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-2"))
				.andExpect(jsonPath("$.result").doesNotExist());

		verifyNoInteractions(couponService);
	}

	@Test
	void createCouponReturnsEventNotFound() throws Exception {
		CouponCreateRequest request = validRequest();
		when(couponService.createCoupon(EVENT_ID, request))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		mockMvc.perform(post("/admin/events/{eventId}/coupons", EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(validRequestJson()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT404-0"))
				.andExpect(jsonPath("$.message").value("존재하지 않는 이벤트입니다."))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void createCouponReturnsInvalidRateDiscountPolicy() throws Exception {
		CouponCreateRequest request = validRequest();
		when(couponService.createCoupon(EVENT_ID, request))
				.thenThrow(new GeneralException(CouponErrorCode.INVALID_RATE_DISCOUNT_POLICY));

		mockMvc.perform(post("/admin/events/{eventId}/coupons", EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(validRequestJson()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COUPON400-4"))
				.andExpect(jsonPath("$.message").value("정률 할인 정책이 올바르지 않습니다."))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	private CouponCreateRequest validRequest() {
		return CouponCreateRequest.builder()
				.name("여름 정률 쿠폰")
				.discountType(DiscountType.RATE)
				.discountValue(20)
				.minOrderAmount(30_000)
				.maxDiscountAmount(10_000)
				.issueStartAt(LocalDateTime.of(2026, 8, 21, 9, 0))
				.issueEndAt(LocalDateTime.of(2026, 8, 30, 23, 59))
				.validDays(7)
				.totalQuantity(100)
				.build();
	}

	private String validRequestJson() {
		return """
				{
				  "name": "여름 정률 쿠폰",
				  "discountType": "RATE",
				  "discountValue": 20,
				  "minOrderAmount": 30000,
				  "maxDiscountAmount": 10000,
				  "issueStartAt": "2026-08-21T09:00:00",
				  "issueEndAt": "2026-08-30T23:59:00",
				  "validDays": 7,
				  "totalQuantity": 100
				}
				""";
	}
}
