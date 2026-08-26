package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;

import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class EventListServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventStatusHistoryRepository eventStatusHistoryRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private EventConverter eventConverter;

    @InjectMocks
    private EventServiceImpl eventService;

    @Test
    void getOpenEventsFiltersByOpenStatusAndPreservesRepositoryOrder() {
        Event first = mock(Event.class);
        Event second = mock(Event.class);
        EventListResponse firstResponse = response(2L, EventStatus.OPEN);
        EventListResponse secondResponse = response(1L, EventStatus.OPEN);
        when(eventRepository.findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN))
                .thenReturn(List.of(first, second));
        when(eventConverter.toListResponse(first)).thenReturn(firstResponse);
        when(eventConverter.toListResponse(second)).thenReturn(secondResponse);

        List<EventListResponse> actual = eventService.getOpenEvents();

        assertEquals(List.of(firstResponse, secondResponse), actual);
        verify(eventRepository).findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN);
    }

    @Test
    void getAllEventsReturnsEveryStatusAndPreservesRepositoryOrder() {
        Event scheduled = mock(Event.class);
        Event open = mock(Event.class);
        Event closed = mock(Event.class);
        EventListResponse scheduledResponse = response(3L, EventStatus.SCHEDULED);
        EventListResponse openResponse = response(2L, EventStatus.OPEN);
        EventListResponse closedResponse = response(1L, EventStatus.CLOSED);
        when(eventRepository.findAllByOrderByCreatedAtDescEventIdDesc())
                .thenReturn(List.of(scheduled, open, closed));
        when(eventConverter.toListResponse(scheduled)).thenReturn(scheduledResponse);
        when(eventConverter.toListResponse(open)).thenReturn(openResponse);
        when(eventConverter.toListResponse(closed)).thenReturn(closedResponse);

        List<EventListResponse> actual = eventService.getAllEvents();

        assertEquals(List.of(scheduledResponse, openResponse, closedResponse), actual);
        verify(eventRepository).findAllByOrderByCreatedAtDescEventIdDesc();
    }

    @Test
    void getOpenEventsReturnsEmptyListWhenNoOpenEventExists() {
        when(eventRepository.findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN))
                .thenReturn(List.of());

        List<EventListResponse> actual = eventService.getOpenEvents();

        assertTrue(actual.isEmpty());
        verifyNoInteractions(eventConverter);
    }

    @Test
    void getAllEventsReturnsEmptyListWhenNoEventExists() {
        when(eventRepository.findAllByOrderByCreatedAtDescEventIdDesc()).thenReturn(List.of());

        List<EventListResponse> actual = eventService.getAllEvents();

        assertTrue(actual.isEmpty());
        verifyNoInteractions(eventConverter);
    }

    @Test
    void getOpenEventsTranslatesDataAccessExceptionToEventError() {
        when(eventRepository.findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN))
                .thenThrow(new DataRetrievalFailureException("event query failed"));

        GeneralException exception = assertThrows(GeneralException.class, eventService::getOpenEvents);

        assertSame(EventErrorCode.EVENT_LIST_QUERY_FAILED, exception.getErrorCode());
        verifyNoInteractions(eventConverter);
    }

    @Test
    void getAllEventsTranslatesDataAccessExceptionToEventError() {
        when(eventRepository.findAllByOrderByCreatedAtDescEventIdDesc())
                .thenThrow(new DataRetrievalFailureException("event query failed"));

        GeneralException exception = assertThrows(GeneralException.class, eventService::getAllEvents);

        assertSame(EventErrorCode.EVENT_LIST_QUERY_FAILED, exception.getErrorCode());
        verifyNoInteractions(eventConverter);
    }

    private EventListResponse response(Long eventId, EventStatus status) {
        return new EventListResponse(
                eventId,
                "이벤트 " + eventId,
                "이벤트 설명",
                LocalDateTime.of(2026, 8, 20, 9, 0),
                LocalDateTime.of(2026, 8, 31, 23, 59),
                status
        );
    }
}
