package com.mycom.petcoupon.event.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.event.dto.req.EventPeriodUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminEventPeriodControllerTest {

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
	void updateEventPeriodReturnsOkResponse() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.openAt(java.time.LocalDateTime.of(2026, 9, 1, 0, 0))
				.closeAt(java.time.LocalDateTime.of(2026, 9, 30, 23, 59))
				.build();
		when(eventService.updateEventPeriod(eq(eventId), any(EventPeriodUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}/period", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "openAt": "2026-09-01T00:00:00",
							  "closeAt": "2026-09-30T23:59:00"
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.eventId").value(eventId))
				.andExpect(jsonPath("$.result.closeAt").value("2026-09-30T23:59:00"));
	}

	@Test
	void updateEventPeriodReturnsNotFoundWhenEventDoesNotExist() throws Exception {
		Long eventId = 99L;
		when(eventService.updateEventPeriod(eq(eventId), any(EventPeriodUpdateRequest.class)))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		mockMvc.perform(patch("/admin/events/{eventId}/period", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "openAt": "2026-09-01T00:00:00",
							  "closeAt": "2026-09-30T23:59:00"
							}
							"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT404-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void updateEventPeriodReturnsBadRequestWhenPeriodIsInvalid() throws Exception {
		Long eventId = 1L;
		when(eventService.updateEventPeriod(eq(eventId), any(EventPeriodUpdateRequest.class)))
				.thenThrow(new GeneralException(EventErrorCode.INVALID_EVENT_PERIOD));

		mockMvc.perform(patch("/admin/events/{eventId}/period", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "openAt": "2026-09-30T23:59:00",
							  "closeAt": "2026-09-01T00:00:00"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT400-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void updateEventPeriodReturnsValidationErrorWhenOpenAtIsMissing() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}/period", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "closeAt": "2026-09-30T23:59:00"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.openAt").value("시작 일시는 필수입니다."));

		verifyNoInteractions(eventService);
	}
}
