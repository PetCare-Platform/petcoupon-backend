package com.mycom.petcoupon.event.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminEventDetailControllerTest {

	@Mock
	private EventService eventService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new AdminEventController(eventService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void getEventDetailReturnsOkResponse() throws Exception {
		Long eventId = 1L;
		EventDetailResponse response = EventDetailResponse.builder()
				.eventId(eventId)
				.name("반려동물 여름 이벤트")
				.description("선착순 쿠폰 이벤트")
				.openAt(LocalDateTime.of(2026, 8, 20, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.status(EventStatus.SCHEDULED)
				.build();
		when(eventService.getEventDetail(eventId)).thenReturn(response);

		mockMvc.perform(get("/admin/events/{eventId}", eventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.eventId").value(eventId))
				.andExpect(jsonPath("$.result.name").value("반려동물 여름 이벤트"))
				.andExpect(jsonPath("$.result.description").value("선착순 쿠폰 이벤트"))
				.andExpect(jsonPath("$.result.openAt").value("2026-08-20T09:00:00"))
				.andExpect(jsonPath("$.result.closeAt").value("2026-08-31T23:59:00"))
				.andExpect(jsonPath("$.result.status").value("SCHEDULED"));
	}

	@Test
	void getEventDetailReturnsNotFoundWhenEventDoesNotExist() throws Exception {
		Long eventId = 99L;
		when(eventService.getEventDetail(eventId))
				.thenThrow(new GeneralException(CommonErrorCode.NOT_FOUND));

		mockMvc.perform(get("/admin/events/{eventId}", eventId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON404-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}
}
