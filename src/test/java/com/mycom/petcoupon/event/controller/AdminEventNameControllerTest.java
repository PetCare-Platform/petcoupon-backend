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

import com.mycom.petcoupon.event.dto.req.EventNameUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminEventNameControllerTest {

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
	void updateEventNameReturnsOkResponse() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.name("연장된 이벤트")
				.build();
		when(eventService.updateEventName(eq(eventId), any(EventNameUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}/name", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "연장된 이벤트"
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.eventId").value(eventId))
				.andExpect(jsonPath("$.result.name").value("연장된 이벤트"));
	}

	@Test
	void updateEventNameReturnsNotFoundWhenEventDoesNotExist() throws Exception {
		Long eventId = 99L;
		when(eventService.updateEventName(eq(eventId), any(EventNameUpdateRequest.class)))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		mockMvc.perform(patch("/admin/events/{eventId}/name", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "연장된 이벤트"
							}
							"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT404-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void updateEventNameReturnsValidationErrorWhenNameIsBlank() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}/name", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "   "
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.name").value("이벤트 이름은 필수입니다."));

		verifyNoInteractions(eventService);
	}

	@Test
	void updateEventNameReturnsValidationErrorWhenNameExceedsMaxLength() throws Exception {
		String tooLongName = "a".repeat(101);

		mockMvc.perform(patch("/admin/events/{eventId}/name", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "%s"
							}
							""".formatted(tooLongName)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.name").value("이벤트 이름은 100자 이하여야 합니다."));

		verifyNoInteractions(eventService);
	}
}
