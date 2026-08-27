package com.mycom.petcoupon.event.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.dto.res.EventCouponResponse;
import com.mycom.petcoupon.event.dto.res.PublicEventDetailResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class PublicEventDetailControllerTest {

	@Mock
	private EventService eventService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new EventController(eventService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void getPublicEventDetailReturnsEventWithCoupons() throws Exception {
		EventCouponResponse coupon = EventCouponResponse.builder()
				.couponId(10L)
				.name("10% 할인 쿠폰")
				.discountType(DiscountType.RATE)
				.discountValue(10)
				.minOrderAmount(20000)
				.maxDiscountAmount(5000)
				.issueStartAt(LocalDateTime.of(2026, 8, 1, 0, 0))
				.issueEndAt(LocalDateTime.of(2026, 8, 31, 23, 59, 59))
				.validDays(30)
				.status(CouponStatus.ACTIVE)
				.build();
		PublicEventDetailResponse response = PublicEventDetailResponse.builder()
				.eventId(1L)
				.name("여름 이벤트")
				.description("이벤트 설명")
				.openAt(LocalDateTime.of(2026, 8, 1, 0, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59, 59))
				.status(EventStatus.OPEN)
				.coupons(List.of(coupon))
				.build();
		when(eventService.getPublicEventDetail(1L)).thenReturn(response);

		mockMvc.perform(get("/events/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.eventId").value(1L))
				.andExpect(jsonPath("$.result.name").value("여름 이벤트"))
				.andExpect(jsonPath("$.result.description").value("이벤트 설명"))
				.andExpect(jsonPath("$.result.openAt").value("2026-08-01T00:00:00"))
				.andExpect(jsonPath("$.result.closeAt").value("2026-08-31T23:59:59"))
				.andExpect(jsonPath("$.result.status").value("OPEN"))
				.andExpect(jsonPath("$.result.coupons.length()").value(1))
				.andExpect(jsonPath("$.result.coupons[0].couponId").value(10L))
				.andExpect(jsonPath("$.result.coupons[0].name").value("10% 할인 쿠폰"))
				.andExpect(jsonPath("$.result.coupons[0].discountType").value("RATE"))
				.andExpect(jsonPath("$.result.coupons[0].discountValue").value(10))
				.andExpect(jsonPath("$.result.coupons[0].minOrderAmount").value(20000))
				.andExpect(jsonPath("$.result.coupons[0].maxDiscountAmount").value(5000))
				.andExpect(jsonPath("$.result.coupons[0].issueStartAt").value("2026-08-01T00:00:00"))
				.andExpect(jsonPath("$.result.coupons[0].issueEndAt").value("2026-08-31T23:59:59"))
				.andExpect(jsonPath("$.result.coupons[0].validDays").value(30))
				.andExpect(jsonPath("$.result.coupons[0].status").value("ACTIVE"));

		verify(eventService).getPublicEventDetail(1L);
	}

	@Test
	void getPublicEventDetailReturnsEmptyArrayWhenEventHasNoCoupon() throws Exception {
		PublicEventDetailResponse response = PublicEventDetailResponse.builder()
				.eventId(1L)
				.name("여름 이벤트")
				.openAt(LocalDateTime.of(2026, 8, 1, 0, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59, 59))
				.status(EventStatus.OPEN)
				.coupons(List.of())
				.build();
		when(eventService.getPublicEventDetail(1L)).thenReturn(response);

		mockMvc.perform(get("/events/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.coupons").isArray())
				.andExpect(jsonPath("$.result.coupons").isEmpty());
	}

	@Test
	void getPublicEventDetailReturnsNotFoundWhenEventDoesNotExist() throws Exception {
		when(eventService.getPublicEventDetail(99L))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		mockMvc.perform(get("/events/99"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT404-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void getPublicEventDetailReturnsNotOpenWhenEventIsNotOpen() throws Exception {
		when(eventService.getPublicEventDetail(1L))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_OPEN));

		mockMvc.perform(get("/events/1"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT404-1"))
				.andExpect(jsonPath("$.message").value("공개 상세 조회할 수 있는 이벤트가 아닙니다."))
				.andExpect(jsonPath("$.result").doesNotExist());
	}
}
