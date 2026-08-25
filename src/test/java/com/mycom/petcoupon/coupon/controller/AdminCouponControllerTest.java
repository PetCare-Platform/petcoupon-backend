package com.mycom.petcoupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.mycom.petcoupon.coupon.dto.req.CouponUpdateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponUpdateResponse;
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
	private static final Long COUPON_ID = 10L;

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

	@Test
	void updateCouponReturnsSuccessResponse() throws Exception {
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 정률 쿠폰").build();
		CouponUpdateResponse response = CouponUpdateResponse.builder()
				.couponId(COUPON_ID)
				.eventId(EVENT_ID)
				.name("가을 정률 쿠폰")
				.discountType(DiscountType.RATE)
				.discountValue(20)
				.minOrderAmount(30_000)
				.maxDiscountAmount(10_000)
				.issueStartAt(LocalDateTime.of(2026, 8, 21, 9, 0))
				.issueEndAt(LocalDateTime.of(2026, 8, 30, 23, 59))
				.validDays(7)
				.totalQuantity(100)
				.status(CouponStatus.READY)
				.build();
		when(couponService.updateCoupon(EVENT_ID, COUPON_ID, request)).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}/coupons/{couponId}", EVENT_ID, COUPON_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "가을 정률 쿠폰"
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.couponId").value(COUPON_ID))
				.andExpect(jsonPath("$.result.name").value("가을 정률 쿠폰"));
	}

	@Test
	void updateCouponReturnsValidationErrorWhenTotalQuantityIsNotPositive() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}/coupons/{couponId}", EVENT_ID, COUPON_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "totalQuantity": 0
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"));

		verifyNoInteractions(couponService);
	}

	// 공백 검증에 쓰는 정규식이 줄바꿈까지 막아버리지 않는지 확인한다. (?s)가 빠지면
	// '.'이 \n에 매칭되지 않아 이 멀쩡한 이름이 400으로 거부된다.
	@Test
	void updateCouponAcceptsNameContainingNewline() throws Exception {
		when(couponService.updateCoupon(eq(EVENT_ID), eq(COUPON_ID), any(CouponUpdateRequest.class)))
				.thenReturn(CouponUpdateResponse.builder().couponId(COUPON_ID).build());

		mockMvc.perform(patch("/admin/events/{eventId}/coupons/{couponId}", EVENT_ID, COUPON_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "가을\\n쿠폰"
							}
							"""))
				.andExpect(status().isOk());
	}

	// 생성 API의 @NotBlank와 대응. name을 아예 안 보내는 건 허용되지만(생략 = 기존값 유지),
	// 보냈는데 전부 공백이면 거부한다.
	@Test
	void updateCouponReturnsValidationErrorWhenNameIsBlank() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}/coupons/{couponId}", EVENT_ID, COUPON_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "   "
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"));

		verifyNoInteractions(couponService);
	}

	@Test
	void updateCouponReturnsEmptyUpdateRequestError() throws Exception {
		when(couponService.updateCoupon(EVENT_ID, COUPON_ID, CouponUpdateRequest.builder().build()))
				.thenThrow(new GeneralException(CouponErrorCode.EMPTY_UPDATE_REQUEST));

		mockMvc.perform(patch("/admin/events/{eventId}/coupons/{couponId}", EVENT_ID, COUPON_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COUPON400-8"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void updateCouponReturnsCouponNotFound() throws Exception {
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 정률 쿠폰").build();
		when(couponService.updateCoupon(EVENT_ID, COUPON_ID, request))
				.thenThrow(new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

		mockMvc.perform(patch("/admin/events/{eventId}/coupons/{couponId}", EVENT_ID, COUPON_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "가을 정률 쿠폰"
							}
							"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COUPON404-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void updateCouponReturnsInvalidCouponStatusForUpdate() throws Exception {
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 정률 쿠폰").build();
		when(couponService.updateCoupon(EVENT_ID, COUPON_ID, request))
				.thenThrow(new GeneralException(CouponErrorCode.INVALID_COUPON_STATUS_FOR_UPDATE));

		mockMvc.perform(patch("/admin/events/{eventId}/coupons/{couponId}", EVENT_ID, COUPON_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "가을 정률 쿠폰"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COUPON400-7"))
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
