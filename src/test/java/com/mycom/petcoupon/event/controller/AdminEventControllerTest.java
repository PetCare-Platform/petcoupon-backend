package com.mycom.petcoupon.event.controller;

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

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminEventControllerTest {

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
	void createEventReturnsCreatedResponse() throws Exception {
		EventCreateRequest request = new EventCreateRequest(
				"반려동물 여름 이벤트",
				"선착순 쿠폰 이벤트",
				LocalDateTime.of(2026, 8, 20, 9, 0),
				LocalDateTime.of(2026, 8, 31, 23, 59)
		);
		EventCreateResponse response = EventCreateResponse.builder()
				.eventId(1L)
				.name(request.name())
				.description(request.description())
				.openAt(request.openAt())
				.closeAt(request.closeAt())
				.status(EventStatus.SCHEDULED)
				.build();
		when(eventService.createEvent(request)).thenReturn(response);

		mockMvc.perform(post("/admin/events")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "반려동물 여름 이벤트",
							  "description": "선착순 쿠폰 이벤트",
							  "openAt": "2026-08-20T09:00:00",
							  "closeAt": "2026-08-31T23:59:00"
							}
							"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("201"))
				.andExpect(jsonPath("$.result.eventId").value(1L))
				.andExpect(jsonPath("$.result.name").value("반려동물 여름 이벤트"))
				.andExpect(jsonPath("$.result.status").value("SCHEDULED"));
	}

	@Test
	void createEventReturnsValidationErrorWhenNameIsBlank() throws Exception {
		mockMvc.perform(post("/admin/events")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": " ",
							  "description": "선착순 쿠폰 이벤트",
							  "openAt": "2026-08-20T09:00:00",
							  "closeAt": "2026-08-31T23:59:00"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.name").value("이벤트 이름은 필수입니다."));

		verifyNoInteractions(eventService);
	}

	@Test
	void createEventReturnsBadRequestWhenPeriodIsInvalid() throws Exception {
		EventCreateRequest request = new EventCreateRequest(
				"반려동물 여름 이벤트",
				"선착순 쿠폰 이벤트",
				LocalDateTime.of(2026, 8, 20, 9, 0),
				LocalDateTime.of(2026, 8, 20, 8, 59)
		);
		when(eventService.createEvent(request))
				.thenThrow(new GeneralException(EventErrorCode.INVALID_EVENT_PERIOD));

		mockMvc.perform(post("/admin/events")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "반려동물 여름 이벤트",
							  "description": "선착순 쿠폰 이벤트",
							  "openAt": "2026-08-20T09:00:00",
							  "closeAt": "2026-08-20T08:59:00"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT400-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void createEventReturnsInvalidJsonErrorWhenDateFormatIsInvalid() throws Exception {
		mockMvc.perform(post("/admin/events")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "반려동물 여름 이벤트",
							  "openAt": "잘못된 날짜",
							  "closeAt": "2026-08-31T23:59:00"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-2"))
				.andExpect(jsonPath("$.result").doesNotExist());

		verifyNoInteractions(eventService);
	}

}
