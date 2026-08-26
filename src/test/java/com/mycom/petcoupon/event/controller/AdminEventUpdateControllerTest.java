package com.mycom.petcoupon.event.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.event.dto.req.EventUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminEventUpdateControllerTest {

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
	void updateEventReturnsOkResponseWhenOnlyNameIsGiven() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.name("연장된 이벤트")
				.build();
		when(eventService.updateEvent(eq(eventId), any(EventUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}", eventId)
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

		ArgumentCaptor<EventUpdateRequest> captor = ArgumentCaptor.forClass(EventUpdateRequest.class);
		verify(eventService).updateEvent(eq(eventId), captor.capture());
		EventUpdateRequest captured = captor.getValue();
		assertEquals("연장된 이벤트", captured.name());
		assertNull(captured.description());
		assertNull(captured.openAt());
		assertNull(captured.closeAt());
	}

	@Test
	void updateEventReturnsOkResponseWhenOnlyDescriptionIsGiven() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.description("연장된 설명")
				.build();
		when(eventService.updateEvent(eq(eventId), any(EventUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "description": "연장된 설명"
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.description").value("연장된 설명"));
	}

	@Test
	void updateEventClearsDescriptionWhenBlankStringIsGiven() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.description(null)
				.build();
		when(eventService.updateEvent(eq(eventId), any(EventUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "description": ""
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.description").doesNotExist());
	}

	@Test
	void updateEventReturnsOkResponseWhenPeriodIsGiven() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.openAt(LocalDateTime.of(2026, 9, 1, 0, 0))
				.closeAt(LocalDateTime.of(2026, 9, 30, 23, 59))
				.build();
		when(eventService.updateEvent(eq(eventId), any(EventUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}", eventId)
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
	void updateEventReturnsOkResponseWhenEveryFieldIsGiven() throws Exception {
		Long eventId = 1L;
		EventUpdateResponse response = EventUpdateResponse.builder()
				.eventId(eventId)
				.name("연장된 이벤트")
				.description("연장된 설명")
				.openAt(LocalDateTime.of(2026, 9, 1, 0, 0))
				.closeAt(LocalDateTime.of(2026, 9, 30, 23, 59))
				.build();
		when(eventService.updateEvent(eq(eventId), any(EventUpdateRequest.class))).thenReturn(response);

		mockMvc.perform(patch("/admin/events/{eventId}", eventId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "연장된 이벤트",
							  "description": "연장된 설명",
							  "openAt": "2026-09-01T00:00:00",
							  "closeAt": "2026-09-30T23:59:00"
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.name").value("연장된 이벤트"))
				.andExpect(jsonPath("$.result.description").value("연장된 설명"))
				.andExpect(jsonPath("$.result.openAt").value("2026-09-01T00:00:00"));
	}

	@Test
	void updateEventReturnsNotFoundWhenEventDoesNotExist() throws Exception {
		Long eventId = 99L;
		when(eventService.updateEvent(eq(eventId), any(EventUpdateRequest.class)))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		mockMvc.perform(patch("/admin/events/{eventId}", eventId)
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
	void updateEventReturnsBadRequestWhenPeriodIsInvalid() throws Exception {
		Long eventId = 1L;
		when(eventService.updateEvent(eq(eventId), any(EventUpdateRequest.class)))
				.thenThrow(new GeneralException(EventErrorCode.INVALID_EVENT_PERIOD));

		mockMvc.perform(patch("/admin/events/{eventId}", eventId)
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
	void updateEventReturnsValidationErrorWhenNoFieldIsGiven() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.anyFieldPresent").value("수정할 항목을 최소 하나 이상 포함해야 합니다."));

		verifyNoInteractions(eventService);
	}

	@Test
	void updateEventReturnsValidationErrorWhenNameIsBlank() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "name": "   "
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-1"))
				.andExpect(jsonPath("$.result.nameNotBlank").value("이벤트 이름은 공백일 수 없습니다."));

		verifyNoInteractions(eventService);
	}

	@Test
	void updateEventReturnsValidationErrorWhenNameExceedsMaxLength() throws Exception {
		String tooLongName = "a".repeat(101);

		mockMvc.perform(patch("/admin/events/{eventId}", 1L)
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

	@Test
	void updateEventReturnsValidationErrorWhenDescriptionExceedsMaxLength() throws Exception {
		String tooLongDescription = "a".repeat(501);

		mockMvc.perform(patch("/admin/events/{eventId}", 1L)
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

	@Test
	void updateEventReturnsInvalidJsonErrorWhenDateFormatIsInvalid() throws Exception {
		mockMvc.perform(patch("/admin/events/{eventId}", 1L)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "openAt": "잘못된 날짜"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON400-2"))
				.andExpect(jsonPath("$.result").doesNotExist());

		verifyNoInteractions(eventService);
	}
}
