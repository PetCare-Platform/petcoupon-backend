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
class AdminEventListControllerTest {

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
	void getEventsUsesDefaultPaginationAndReturnsEveryStatus() throws Exception {
		List<EventListResponse> content = List.of(
				response(3L, "예정 이벤트", EventStatus.SCHEDULED),
				response(2L, "진행 이벤트", EventStatus.OPEN),
				response(1L, "종료 이벤트", EventStatus.CLOSED)
		);
		EventPageRequest defaultRequest = new EventPageRequest(0, 20);
		when(eventService.getAllEvents(defaultRequest))
				.thenReturn(new EventPageResponse(content, 0, 20, 3, 1, true, true));

		mockMvc.perform(get("/admin/events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.code").value("200"))
				.andExpect(jsonPath("$.result.content.length()").value(3))
				.andExpect(jsonPath("$.result.content[0].eventId").value(3L))
				.andExpect(jsonPath("$.result.content[0].status").value("SCHEDULED"))
				.andExpect(jsonPath("$.result.content[1].eventId").value(2L))
				.andExpect(jsonPath("$.result.content[1].status").value("OPEN"))
				.andExpect(jsonPath("$.result.content[2].eventId").value(1L))
				.andExpect(jsonPath("$.result.content[2].status").value("CLOSED"))
				.andExpect(jsonPath("$.result.page").value(0))
				.andExpect(jsonPath("$.result.size").value(20))
				.andExpect(jsonPath("$.result.totalElements").value(3));

		verify(eventService).getAllEvents(defaultRequest);
	}

	@Test
	void getEventsAcceptsExplicitPagination() throws Exception {
		EventPageRequest request = new EventPageRequest(1, 50);
		when(eventService.getAllEvents(request))
				.thenReturn(new EventPageResponse(List.of(), 1, 50, 75, 2, false, true));

		mockMvc.perform(get("/admin/events").param("page", "1").param("size", "50"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.page").value(1))
				.andExpect(jsonPath("$.result.size").value(50))
				.andExpect(jsonPath("$.result.totalPages").value(2));

		verify(eventService).getAllEvents(request);
	}

	@Test
	void getEventsRejectsUnsupportedPageSize() throws Exception {
		mockMvc.perform(get("/admin/events").param("size", "25"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT400-3"));

		verifyNoInteractions(eventService);
	}

	@Test
	void getEventsReturnsEventErrorResponseWhenListQueryFails() throws Exception {
		EventPageRequest defaultRequest = new EventPageRequest(0, 20);
		when(eventService.getAllEvents(defaultRequest))
				.thenThrow(new GeneralException(EventErrorCode.EVENT_LIST_QUERY_FAILED));

		mockMvc.perform(get("/admin/events"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("EVENT500-0"))
				.andExpect(jsonPath("$.message").value("이벤트 목록 조회에 실패했습니다."))
				.andExpect(jsonPath("$.result").doesNotExist());
	}

	private EventListResponse response(Long eventId, String name, EventStatus status) {
		return new EventListResponse(
				eventId,
				name,
				"이벤트 설명",
				LocalDateTime.of(2026, 8, 20, 9, 0),
				LocalDateTime.of(2026, 8, 31, 23, 59),
				status
		);
	}
}
