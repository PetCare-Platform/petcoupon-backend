package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.req.EventPageRequest;
import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.dto.res.EventPageResponse;
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
	void getOpenEventsFiltersByOpenStatusAndPreservesPageMetadata() {
		Event first = mock(Event.class);
		Event second = mock(Event.class);
		EventListResponse firstResponse = response(12L, EventStatus.OPEN);
		EventListResponse secondResponse = response(11L, EventStatus.OPEN);
		EventPageRequest request = new EventPageRequest(1, 10);
		Pageable pageable = PageRequest.of(1, 10);
		Page<Event> eventPage = new PageImpl<>(List.of(first, second), pageable, 22);
		when(eventRepository.findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN, pageable))
				.thenReturn(eventPage);
		when(eventConverter.toListResponse(first)).thenReturn(firstResponse);
		when(eventConverter.toListResponse(second)).thenReturn(secondResponse);

		EventPageResponse actual = eventService.getOpenEvents(request);

		assertEquals(List.of(firstResponse, secondResponse), actual.content());
		assertEquals(1, actual.page());
		assertEquals(10, actual.size());
		assertEquals(22, actual.totalElements());
		assertEquals(3, actual.totalPages());
		assertFalse(actual.first());
		assertFalse(actual.last());
		verify(eventRepository).findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN, pageable);
	}

	@Test
	void getAllEventsReturnsEveryStatusAndPreservesRepositoryOrder() {
		Event scheduled = mock(Event.class);
		Event open = mock(Event.class);
		Event closed = mock(Event.class);
		EventListResponse scheduledResponse = response(3L, EventStatus.SCHEDULED);
		EventListResponse openResponse = response(2L, EventStatus.OPEN);
		EventListResponse closedResponse = response(1L, EventStatus.CLOSED);
		EventPageRequest request = new EventPageRequest(0, 20);
		Pageable pageable = PageRequest.of(0, 20);
		when(eventRepository.findAllByOrderByCreatedAtDescEventIdDesc(pageable))
				.thenReturn(new PageImpl<>(List.of(scheduled, open, closed), pageable, 3));
		when(eventConverter.toListResponse(scheduled)).thenReturn(scheduledResponse);
		when(eventConverter.toListResponse(open)).thenReturn(openResponse);
		when(eventConverter.toListResponse(closed)).thenReturn(closedResponse);

		EventPageResponse actual = eventService.getAllEvents(request);

		assertEquals(List.of(scheduledResponse, openResponse, closedResponse), actual.content());
		assertEquals(3, actual.totalElements());
		assertEquals(1, actual.totalPages());
		assertTrue(actual.first());
		assertTrue(actual.last());
		verify(eventRepository).findAllByOrderByCreatedAtDescEventIdDesc(pageable);
	}

	@Test
	void getOpenEventsReturnsEmptyPageWhenNoOpenEventExists() {
		EventPageRequest request = new EventPageRequest(0, 20);
		Pageable pageable = PageRequest.of(0, 20);
		when(eventRepository.findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN, pageable))
				.thenReturn(Page.empty(pageable));

		EventPageResponse actual = eventService.getOpenEvents(request);

		assertTrue(actual.content().isEmpty());
		assertEquals(0, actual.totalElements());
		assertEquals(0, actual.totalPages());
		verifyNoInteractions(eventConverter);
	}

	@Test
	void getAllEventsReturnsEmptyPageWhenNoEventExists() {
		EventPageRequest request = new EventPageRequest(0, 100);
		Pageable pageable = PageRequest.of(0, 100);
		when(eventRepository.findAllByOrderByCreatedAtDescEventIdDesc(pageable))
				.thenReturn(Page.empty(pageable));

		EventPageResponse actual = eventService.getAllEvents(request);

		assertTrue(actual.content().isEmpty());
		assertEquals(100, actual.size());
		verifyNoInteractions(eventConverter);
	}

	@Test
	void getOpenEventsTranslatesDataAccessExceptionToEventError() {
		EventPageRequest request = new EventPageRequest(0, 20);
		Pageable pageable = PageRequest.of(0, 20);
		when(eventRepository.findAllByStatusOrderByCreatedAtDescEventIdDesc(EventStatus.OPEN, pageable))
				.thenThrow(new DataRetrievalFailureException("event query failed"));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.getOpenEvents(request)
		);

		assertSame(EventErrorCode.EVENT_LIST_QUERY_FAILED, exception.getErrorCode());
		verifyNoInteractions(eventConverter);
	}

	@Test
	void getAllEventsTranslatesDataAccessExceptionToEventError() {
		EventPageRequest request = new EventPageRequest(0, 20);
		Pageable pageable = PageRequest.of(0, 20);
		when(eventRepository.findAllByOrderByCreatedAtDescEventIdDesc(pageable))
				.thenThrow(new DataRetrievalFailureException("event query failed"));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.getAllEvents(request)
		);

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
