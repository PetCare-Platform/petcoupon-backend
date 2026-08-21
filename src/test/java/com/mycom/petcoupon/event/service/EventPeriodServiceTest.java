package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
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
import com.mycom.petcoupon.event.dto.req.EventPeriodUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class EventPeriodServiceTest {

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
	void updateEventPeriodOverwritesOpenAtAndCloseAtAndReturnsConvertedResponse() {
		Long eventId = 1L;
		LocalDateTime openAt = LocalDateTime.of(2026, 9, 1, 0, 0);
		LocalDateTime closeAt = LocalDateTime.of(2026, 9, 30, 23, 59);
		EventPeriodUpdateRequest request = new EventPeriodUpdateRequest(openAt, closeAt);
		Event event = mock(Event.class);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEventPeriod(eventId, request);

		assertSame(expected, actual);
		verify(event).updatePeriod(openAt, closeAt);
	}

	@Test
	void updateEventPeriodThrowsInvalidPeriodWhenCloseAtIsNotAfterOpenAt() {
		Long eventId = 1L;
		LocalDateTime openAt = LocalDateTime.of(2026, 9, 30, 23, 59);
		LocalDateTime closeAt = LocalDateTime.of(2026, 9, 1, 0, 0);
		EventPeriodUpdateRequest request = new EventPeriodUpdateRequest(openAt, closeAt);

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventPeriod(eventId, request)
		);

		assertSame(EventErrorCode.INVALID_EVENT_PERIOD, exception.getErrorCode());
		verifyNoInteractions(eventRepository);
		verifyNoInteractions(eventConverter);
	}

	@Test
	void updateEventPeriodThrowsNotFoundWhenEventDoesNotExist() {
		Long eventId = 99L;
		EventPeriodUpdateRequest request = new EventPeriodUpdateRequest(
				LocalDateTime.of(2026, 9, 1, 0, 0),
				LocalDateTime.of(2026, 9, 30, 23, 59)
		);
		when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventPeriod(eventId, request)
		);

		assertSame(EventErrorCode.EVENT_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(eventConverter);
	}
}
