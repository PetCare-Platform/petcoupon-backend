package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.req.EventUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class EventUpdateServiceTest {

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
	void updateEventOverwritesNameAndReturnsConvertedResponse() {
		Long eventId = 1L;
		EventUpdateRequest request = EventUpdateRequest.builder().name("연장된 이벤트").build();
		Event event = mock(Event.class);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).name("연장된 이벤트").build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEvent(eventId, request);

		assertSame(expected, actual);
		verify(event).updateName("연장된 이벤트");
		verify(event, never()).updateDescription(any());
		verify(event, never()).updatePeriod(any(), any());
	}

	@Test
	void updateEventOverwritesDescription() {
		Long eventId = 1L;
		EventUpdateRequest request = EventUpdateRequest.builder().description("연장된 설명").build();
		Event event = mock(Event.class);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEvent(eventId, request);

		assertSame(expected, actual);
		verify(event).updateDescription("연장된 설명");
	}

	@Test
	void updateEventClearsDescriptionWhenGivenBlankString() {
		Long eventId = 1L;
		EventUpdateRequest request = EventUpdateRequest.builder().description("").build();
		Event event = mock(Event.class);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEvent(eventId, request);

		assertSame(expected, actual);
		verify(event).updateDescription(null);
	}

	@Test
	void updateEventKeepsDescriptionWhenFieldIsOmitted() {
		Long eventId = 1L;
		EventUpdateRequest request = EventUpdateRequest.builder().name("연장된 이벤트").build();
		Event event = mock(Event.class);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		when(eventConverter.toUpdateResponse(event))
				.thenReturn(EventUpdateResponse.builder().eventId(eventId).build());

		eventService.updateEvent(eventId, request);

		verify(event, never()).updateDescription(any());
	}

	@Test
	void updateEventOverwritesOpenAtAndCloseAt() {
		Long eventId = 1L;
		LocalDateTime openAt = LocalDateTime.of(2026, 9, 1, 0, 0);
		LocalDateTime closeAt = LocalDateTime.of(2026, 9, 30, 23, 59);
		EventUpdateRequest request = EventUpdateRequest.builder().openAt(openAt).closeAt(closeAt).build();
		Event event = mock(Event.class);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEvent(eventId, request);

		assertSame(expected, actual);
		verify(event).updatePeriod(openAt, closeAt);
	}

	@Test
	void updateEventFillsMissingCloseAtFromStoredValue() {
		Long eventId = 1L;
		LocalDateTime openAt = LocalDateTime.of(2026, 9, 1, 0, 0);
		LocalDateTime storedCloseAt = LocalDateTime.of(2026, 9, 30, 23, 59);
		EventUpdateRequest request = EventUpdateRequest.builder().openAt(openAt).build();
		Event event = mock(Event.class);
		when(event.getCloseAt()).thenReturn(storedCloseAt);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEvent(eventId, request);

		assertSame(expected, actual);
		verify(event).updatePeriod(openAt, storedCloseAt);
	}

	@Test
	void updateEventFillsMissingOpenAtFromStoredValue() {
		Long eventId = 1L;
		LocalDateTime storedOpenAt = LocalDateTime.of(2026, 9, 1, 0, 0);
		LocalDateTime closeAt = LocalDateTime.of(2026, 10, 31, 23, 59);
		EventUpdateRequest request = EventUpdateRequest.builder().closeAt(closeAt).build();
		Event event = mock(Event.class);
		when(event.getOpenAt()).thenReturn(storedOpenAt);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEvent(eventId, request);

		assertSame(expected, actual);
		verify(event).updatePeriod(storedOpenAt, closeAt);
	}

	@Test
	void updateEventAppliesEveryFieldAtOnce() {
		Long eventId = 1L;
		LocalDateTime openAt = LocalDateTime.of(2026, 9, 1, 0, 0);
		LocalDateTime closeAt = LocalDateTime.of(2026, 9, 30, 23, 59);
		EventUpdateRequest request = EventUpdateRequest.builder()
				.name("연장된 이벤트")
				.description("연장된 설명")
				.openAt(openAt)
				.closeAt(closeAt)
				.build();
		Event event = mock(Event.class);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEvent(eventId, request);

		assertSame(expected, actual);
		verify(event).updateName("연장된 이벤트");
		verify(event).updateDescription("연장된 설명");
		verify(event).updatePeriod(openAt, closeAt);
	}

	@Test
	void updateEventThrowsInvalidPeriodWhenCloseAtIsNotAfterOpenAt() {
		Long eventId = 1L;
		LocalDateTime openAt = LocalDateTime.of(2026, 9, 30, 23, 59);
		LocalDateTime closeAt = LocalDateTime.of(2026, 9, 1, 0, 0);
		EventUpdateRequest request = EventUpdateRequest.builder()
				.name("연장된 이벤트")
				.openAt(openAt)
				.closeAt(closeAt)
				.build();
		Event event = mock(Event.class);
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEvent(eventId, request)
		);

		assertSame(EventErrorCode.INVALID_EVENT_PERIOD, exception.getErrorCode());
		verify(event, never()).updateName(any());
		verify(event, never()).updatePeriod(any(), any());
		verifyNoInteractions(eventConverter);
	}

	@Test
	void updateEventThrowsInvalidPeriodWhenOnlyOpenAtBreaksStoredCloseAt() {
		Long eventId = 1L;
		LocalDateTime openAt = LocalDateTime.of(2026, 10, 1, 0, 0);
		EventUpdateRequest request = EventUpdateRequest.builder().openAt(openAt).build();
		Event event = mock(Event.class);
		when(event.getCloseAt()).thenReturn(LocalDateTime.of(2026, 9, 30, 23, 59));
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEvent(eventId, request)
		);

		assertSame(EventErrorCode.INVALID_EVENT_PERIOD, exception.getErrorCode());
		verifyNoInteractions(eventConverter);
	}

	@Test
	void updateEventThrowsNotFoundWhenEventDoesNotExist() {
		Long eventId = 99L;
		EventUpdateRequest request = EventUpdateRequest.builder().name("연장된 이벤트").build();
		when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEvent(eventId, request)
		);

		assertSame(EventErrorCode.EVENT_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(eventConverter);
	}
}
