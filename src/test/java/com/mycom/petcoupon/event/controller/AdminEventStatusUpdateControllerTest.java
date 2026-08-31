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

import com.mycom.petcoupon.event.dto.req.EventStatusUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminEventStatusUpdateControllerTest {

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
	void updateEventStatusReturnsOkResponse() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.status(EventStatus.OPEN)
				.build();
		when(eventService.updateEventStatus(eq(eventId), any(EventStatusUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}/status", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "status": "OPEN"
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.eventId").value(eventId))
				.andExpect(jsonPath("$.result.status").value("OPEN"));
	}

	@Test
	void updateEventStatusReturnsNotFoundWhenEventDoesNotExist() throws Exception {
		Long eventId = 99L;
		when(eventService.updateEventStatus(eq(eventId), any(EventStatusUpdateRequest.class)))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		mockMvc.perform(patch("/admin/events/{eventId}/status", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "status": "OPEN"
							}
							"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT404-0"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void updateEventStatusReturnsValidationErrorWhenStatusIsMissing() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}/status", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.status").value("상태값은 필수입니다."));

		verifyNoInteractions(eventService);
	}

	@Test
	void updateEventStatusReturnsBadRequestWhenStatusIsUnchanged() throws Exception {
		Long eventId = 1L;
		when(eventService.updateEventStatus(eq(eventId), any(EventStatusUpdateRequest.class)))
				.thenThrow(new GeneralException(EventErrorCode.SAME_EVENT_STATUS));

		mockMvc.perform(patch("/admin/events/{eventId}/status", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "status": "OPEN"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT400-1"))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	@Test
	void updateEventStatusReturnsBadRequestWhenStatusIsInvalidEnumValue() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}/status", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "status": "NOT_A_STATUS"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-2"));

		verifyNoInteractions(eventService);
	}
}
