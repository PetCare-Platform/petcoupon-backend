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

import com.mycom.petcoupon.event.dto.req.EventDescriptionUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminEventDescriptionControllerTest {

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
	void updateEventDescriptionReturnsOkResponse() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.description("연장된 설명")
				.build();
		when(eventService.updateEventDescription(eq(eventId), any(EventDescriptionUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}/description", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "description": "연장된 설명"
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.eventId").value(eventId))
				.andExpect(jsonPath("$.result.description").value("연장된 설명"));
	}

	@Test
	void updateEventDescriptionClearsDescriptionWhenNull() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.description(null)
				.build();
		when(eventService.updateEventDescription(eq(eventId), any(EventDescriptionUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}/description", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "description": null
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.description").doesNotExist());
	}

	@Test
	void updateEventDescriptionReturnsNotFoundWhenEventDoesNotExist() throws Exception {
		Long eventId = 99L;
		when(eventService.updateEventDescription(eq(eventId), any(EventDescriptionUpdateRequest.class)))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		mockMvc.perform(patch("/admin/events/{eventId}/description", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "description": "연장된 설명"
							}
							"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT404-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void updateEventDescriptionReturnsValidationErrorWhenDescriptionExceedsMaxLength() throws Exception {
		String tooLongDescription = "a".repeat(501);

		mockMvc.perform(patch("/admin/events/{eventId}/description", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "description": "%s"
							}
							""".formatted(tooLongDescription)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.description").value("이벤트 설명은 500자 이하여야 합니다."));

		verifyNoInteractions(eventService);
	}
}
