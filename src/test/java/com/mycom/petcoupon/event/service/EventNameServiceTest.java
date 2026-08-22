package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.req.EventNameUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class EventNameServiceTest {

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
	void updateEventNameOverwritesNameAndReturnsConvertedResponse() {
		Long eventId = 1L;
		EventNameUpdateRequest request = new EventNameUpdateRequest("연장된 이벤트");
		Event event = mock(Event.class);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).name("연장된 이벤트").build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEventName(eventId, request);

		assertSame(expected, actual);
		verify(event).updateName("연장된 이벤트");
	}

	@Test
	void updateEventNameThrowsNotFoundWhenEventDoesNotExist() {
		Long eventId = 99L;
		EventNameUpdateRequest request = new EventNameUpdateRequest("연장된 이벤트");
		when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventName(eventId, request)
		);

		assertSame(EventErrorCode.EVENT_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(eventConverter);
	}
}
