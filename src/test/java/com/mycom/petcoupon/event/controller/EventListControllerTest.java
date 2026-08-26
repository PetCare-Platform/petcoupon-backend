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
    void getEventsReturnsOpenEventList() throws Exception {
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
        when(eventService.getOpenEvents()).thenReturn(List.of(first, second));

        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].eventId").value(2L))
                .andExpect(jsonPath("$.result[0].name").value("가을 반려동물 이벤트"))
                .andExpect(jsonPath("$.result[0].description").value("가을맞이 쿠폰 이벤트"))
                .andExpect(jsonPath("$.result[0].openAt").value("2026-09-01T09:00:00"))
                .andExpect(jsonPath("$.result[0].closeAt").value("2026-09-15T23:59:00"))
                .andExpect(jsonPath("$.result[0].status").value("OPEN"))
                .andExpect(jsonPath("$.result[1].eventId").value(1L))
                .andExpect(jsonPath("$.result[1].status").value("OPEN"));
    }

    @Test
    void getEventsReturnsEmptyListWhenNoOpenEventExists() throws Exception {
        when(eventService.getOpenEvents()).thenReturn(List.of());

        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result").isArray())
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void getEventsReturnsEventErrorResponseWhenListQueryFails() throws Exception {
        when(eventService.getOpenEvents())
                .thenThrow(new GeneralException(EventErrorCode.EVENT_LIST_QUERY_FAILED));

        mockMvc.perform(get("/events"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("EVENT500-0"))
                .andExpect(jsonPath("$.message").value("이벤트 목록 조회에 실패했습니다."))
                .andExpect(jsonPath("$.result").doesNotExist());
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
