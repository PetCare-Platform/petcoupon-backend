package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class EventStatusServiceTest {

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
	void getEventStatusReturnsStatusOnlyResponse() {
		Long eventId = 1L;
		EventStatusResponse expected = new EventStatusResponse(eventId, EventStatus.OPEN);

		when(eventRepository.findStatusByEventId(eventId)).thenReturn(Optional.of(EventStatus.OPEN));
		when(eventConverter.toStatusResponse(eventId, EventStatus.OPEN)).thenReturn(expected);

		EventStatusResponse actual = eventService.getEventStatus(eventId);

		assertSame(expected, actual);
	}

	@Test
	void getEventStatusThrowsNotFoundWhenEventDoesNotExist() {
		Long eventId = 99L;
		when(eventRepository.findStatusByEventId(eventId)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.getEventStatus(eventId)
		);

		assertSame(CommonErrorCode.NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(eventConverter);
	}
}
