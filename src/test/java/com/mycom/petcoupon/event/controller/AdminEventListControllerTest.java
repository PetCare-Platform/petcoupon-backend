package com.mycom.petcoupon.event.controller;

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

import com.mycom.petcoupon.event.dto.res.EventListResponse;
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
    void getEventsReturnsEventsRegardlessOfStatus() throws Exception {
        List<EventListResponse> responses = List.of(
                response(3L, "예정 이벤트", EventStatus.SCHEDULED),
                response(2L, "진행 이벤트", EventStatus.OPEN),
                response(1L, "종료 이벤트", EventStatus.CLOSED)
        );
        when(eventService.getAllEvents()).thenReturn(responses);

        mockMvc.perform(get("/admin/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.length()").value(3))
                .andExpect(jsonPath("$.result[0].eventId").value(3L))
                .andExpect(jsonPath("$.result[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.result[1].eventId").value(2L))
                .andExpect(jsonPath("$.result[1].status").value("OPEN"))
                .andExpect(jsonPath("$.result[2].eventId").value(1L))
                .andExpect(jsonPath("$.result[2].status").value("CLOSED"));
    }

    @Test
    void getEventsReturnsEventErrorResponseWhenListQueryFails() throws Exception {
        when(eventService.getAllEvents())
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
