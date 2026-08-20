package com.mycom.petcoupon.coupon.controller;

import static org.mockito.Mockito.verify;
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
import com.mycom.petcoupon.coupon.service.CouponService;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminCouponControllerTest {

	private static final Long EVENT_ID = 1L;
	private static final String CREATE_COUPON_URL = "/admin/events/{eventId}/coupons";
	private static final String VALID_REQUEST_JSON = """
			{
			  "name": "신규 가입 쿠폰",
			  "discountType": "FIXED_AMOUNT",
			  "discountValue": 5000,
			  "minOrderAmount": 30000,
			  "maxDiscountAmount": null,
			  "issueStartAt": "2026-08-21T09:00:00",
			  "issueEndAt": "2026-08-31T23:59:00",
			  "validDays": 30,
			  "totalQuantity": 100
			}
			""";

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
		CouponCreateRequest request = createRequest();
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

		mockMvc.perform(post(CREATE_COUPON_URL, EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(VALID_REQUEST_JSON))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("201"))
				.andExpect(jsonPath("$.result.couponId").value(10L))
				.andExpect(jsonPath("$.result.eventId").value(EVENT_ID))
				.andExpect(jsonPath("$.result.name").value("신규 가입 쿠폰"))
				.andExpect(jsonPath("$.result.discountType").value("FIXED_AMOUNT"))
				.andExpect(jsonPath("$.result.discountValue").value(5000))
				.andExpect(jsonPath("$.result.minOrderAmount").value(30000))
				.andExpect(jsonPath("$.result.validDays").value(30))
				.andExpect(jsonPath("$.result.totalQuantity").value(100))
				.andExpect(jsonPath("$.result.status").value("READY"));

		verify(couponService).createCoupon(EVENT_ID, request);
	}

	@Test
	void createCouponReturnsValidationErrorWhenNameIsBlank() throws Exception {
		mockMvc.perform(post(CREATE_COUPON_URL, EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": " ",
							  "discountType": "FIXED_AMOUNT",
							  "discountValue": 5000,
							  "minOrderAmount": 30000,
							  "issueStartAt": "2026-08-21T09:00:00",
							  "issueEndAt": "2026-08-31T23:59:00",
							  "validDays": 30,
							  "totalQuantity": 100
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
				.andExpect(jsonPath("$.result.name").value("쿠폰 이름은 필수입니다."));

		verifyNoInteractions(couponService);
	}

	@Test
	void createCouponReturnsInvalidJsonErrorWhenDiscountTypeIsInvalid() throws Exception {
		mockMvc.perform(post(CREATE_COUPON_URL, EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(VALID_REQUEST_JSON.replace("FIXED_AMOUNT", "UNKNOWN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-2"))
				.andExpect(jsonPath("$.message").value("요청 JSON 형식이 올바르지 않습니다."))
				.andExpect(jsonPath("$.result").doesNotExist());

		verifyNoInteractions(couponService);
	}

	@Test
	void createCouponReturnsNotFoundWhenEventDoesNotExist() throws Exception {
		CouponCreateRequest request = createRequest();
		when(couponService.createCoupon(EVENT_ID, request))
				.thenThrow(new GeneralException(CommonErrorCode.NOT_FOUND));

		mockMvc.perform(post(CREATE_COUPON_URL, EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(VALID_REQUEST_JSON))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON404-0"))
				.andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void createCouponReturnsBadRequestWhenServiceRejectsRequest() throws Exception {
		CouponCreateRequest request = createRequest();
		when(couponService.createCoupon(EVENT_ID, request))
				.thenThrow(new GeneralException(CommonErrorCode.BAD_REQUEST));

		mockMvc.perform(post(CREATE_COUPON_URL, EVENT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content(VALID_REQUEST_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-0"))
				.andExpect(jsonPath("$.message").value("잘못된 요청입니다."))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	private CouponCreateRequest createRequest() {
		return new CouponCreateRequest(
				"신규 가입 쿠폰",
				DiscountType.FIXED_AMOUNT,
				5000,
				30000,
				null,
				LocalDateTime.of(2026, 8, 21, 9, 0),
				LocalDateTime.of(2026, 8, 31, 23, 59),
				30,
				100
		);
	}
}
