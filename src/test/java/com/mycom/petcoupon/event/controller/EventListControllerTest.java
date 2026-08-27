package com.mycom.petcoupon.event.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.event.dto.req.EventPageRequest;
import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.dto.res.EventPageResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class EventListControllerTest {

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
	void getEventsUsesDefaultPaginationAndReturnsOpenEventPage() throws Exception {
		EventListResponse first = response(
				2L,
				"가을 반려동물 이벤트",
				"가을맞이 쿠폰 이벤트",
				LocalDateTime.of(2026, 9, 1, 9, 0),
				LocalDateTime.of(2026, 9, 15, 23, 59),
				EventStatus.OPEN
		);
		EventListResponse second = response(
				1L,
				"여름 반려동물 이벤트",
				"선착순 쿠폰 이벤트",
				LocalDateTime.of(2026, 8, 20, 9, 0),
				LocalDateTime.of(2026, 8, 31, 23, 59),
				EventStatus.OPEN
		);
		EventPageRequest defaultRequest = new EventPageRequest(0, 20);
		EventPageResponse response = pageResponse(List.of(first, second), 0, 20, 22, 2, true, false);
		when(eventService.getOpenEvents(defaultRequest)).thenReturn(response);

		mockMvc.perform(get("/events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.content.length()").value(2))
				.andExpect(jsonPath("$.result.content[0].eventId").value(2L))
				.andExpect(jsonPath("$.result.content[0].name").value("가을 반려동물 이벤트"))
				.andExpect(jsonPath("$.result.content[0].description").value("가을맞이 쿠폰 이벤트"))
				.andExpect(jsonPath("$.result.content[0].openAt").value("2026-09-01T09:00:00"))
				.andExpect(jsonPath("$.result.content[0].closeAt").value("2026-09-15T23:59:00"))
				.andExpect(jsonPath("$.result.content[0].status").value("OPEN"))
				.andExpect(jsonPath("$.result.content[1].eventId").value(1L))
				.andExpect(jsonPath("$.result.page").value(0))
				.andExpect(jsonPath("$.result.size").value(20))
				.andExpect(jsonPath("$.result.totalElements").value(22))
				.andExpect(jsonPath("$.result.totalPages").value(2))
				.andExpect(jsonPath("$.result.first").value(true))
				.andExpect(jsonPath("$.result.last").value(false));

		verify(eventService).getOpenEvents(defaultRequest);
	}

	@Test
	void getEventsReturnsEmptyContentWhenNoOpenEventExists() throws Exception {
		EventPageRequest defaultRequest = new EventPageRequest(0, 20);
		when(eventService.getOpenEvents(defaultRequest))
				.thenReturn(pageResponse(List.of(), 0, 20, 0, 0, true, true));

		mockMvc.perform(get("/events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.content").isArray())
				.andExpect(jsonPath("$.result.content").isEmpty())
				.andExpect(jsonPath("$.result.totalElements").value(0))
				.andExpect(jsonPath("$.result.totalPages").value(0));
	}

	@ParameterizedTest
	@ValueSource(ints = {10, 50, 100})
	void getEventsAcceptsSupportedPageSizes(int size) throws Exception {
		EventPageRequest request = new EventPageRequest(2, size);
		when(eventService.getOpenEvents(request))
				.thenReturn(pageResponse(List.of(), 2, size, 0, 0, false, true));

		mockMvc.perform(get("/events").param("page", "2").param("size", String.valueOf(size)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.page").value(2))
				.andExpect(jsonPath("$.result.size").value(size));

		verify(eventService).getOpenEvents(request);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "30", "abc"})
	void getEventsRejectsUnsupportedPageSizes(String size) throws Exception {
		mockMvc.perform(get("/events").param("size", size))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT400-3"));

		verifyNoInteractions(eventService);
	}

	@ParameterizedTest
	@ValueSource(strings = {"-1", "abc"})
	void getEventsRejectsInvalidPageNumbers(String page) throws Exception {
		mockMvc.perform(get("/events").param("page", page))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EVENT400-3"));

		verifyNoInteractions(eventService);
	}

	@Test
	void getEventsReturnsEventErrorResponseWhenListQueryFails() throws Exception {
		EventPageRequest defaultRequest = new EventPageRequest(0, 20);
		when(eventService.getOpenEvents(defaultRequest))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_LIST_QUERY_FAILED));

		mockMvc.perform(get("/events"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT500-0"))
				.andExpect(jsonPath("$.message").value("이벤트 목록 조회에 실패했습니다."))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	private EventPageResponse pageResponse(
			List<EventListResponse> content,
			int page,
			int size,
			long totalElements,
			int totalPages,
			boolean first,
			boolean last
	) {
		return new EventPageResponse(content, page, size, totalElements, totalPages, first, last);
	}

	private EventListResponse response(
			Long eventId,
			String name,
			String description,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			EventStatus status
	) {
		return new EventListResponse(eventId, name, description, openAt, closeAt, status);
	}
}
