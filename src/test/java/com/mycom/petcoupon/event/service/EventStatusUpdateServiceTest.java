package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.mycom.petcoupon.event.dto.req.EventStatusUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.EventStatusHistory;
import com.mycom.petcoupon.event.entity.enums.ActorType;
import com.mycom.petcoupon.event.entity.enums.EventHistoryStatus;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.entity.enums.UserStatus;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class EventStatusUpdateServiceTest {

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
	void updateEventStatusOverwritesStatusAndRecordsHistory() {
		Long eventId = 1L;
		EventStatusUpdateRequest request = new EventStatusUpdateRequest(EventStatus.OPEN, "선착순 마감으로 오픈");
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.SCHEDULED);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

		AppUser admin = mock(AppUser.class);
		when(admin.getUserId()).thenReturn(10L);
		when(appUserRepository.findFirstByRoleAndStatusOrderByUserIdAsc(UserRole.ROLE_ADMIN, UserStatus.ACTIVE))
				.thenReturn(Optional.of(admin));
		when(eventRepository.updateStatusIfMatches(eventId, EventStatus.SCHEDULED, EventStatus.OPEN))
				.thenReturn(1);

		EventUpdateResponse expected = EventUpdateResponse.builder().eventId(eventId).status(EventStatus.OPEN).build();
		when(eventConverter.toUpdateResponse(event)).thenReturn(expected);

		EventUpdateResponse actual = eventService.updateEventStatus(eventId, request);

		assertSame(expected, actual);
		verify(event).updateStatus(EventStatus.OPEN);
		verify(eventStatusHistoryRepository).save(argThat((EventStatusHistory history) ->
				history.getFromStatus() == EventHistoryStatus.SCHEDULED
						&& history.getToStatus() == EventHistoryStatus.OPEN
						&& history.getActorType() == ActorType.ADMIN
						&& history.getActorId().equals(10L)
						&& history.getReason().equals("선착순 마감으로 오픈")
		));
	}

	@Test
	void updateEventStatusThrowsSameStatusWhenStatusIsUnchanged() {
		Long eventId = 1L;
		EventStatusUpdateRequest request = new EventStatusUpdateRequest(EventStatus.OPEN, null);
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.OPEN);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventStatus(eventId, request)
		);

		assertSame(EventErrorCode.SAME_EVENT_STATUS, exception.getErrorCode());
		verifyNoInteractions(appUserRepository);
		verifyNoInteractions(eventStatusHistoryRepository);
		verifyNoInteractions(eventConverter);
	}

	@Test
	void updateEventStatusThrowsInvalidTransitionWhenSkippingOpen() {
		Long eventId = 1L;
		EventStatusUpdateRequest request = new EventStatusUpdateRequest(EventStatus.CLOSED, null);
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.SCHEDULED);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventStatus(eventId, request)
		);

		assertSame(EventErrorCode.INVALID_EVENT_STATUS_TRANSITION, exception.getErrorCode());
		verifyNoInteractions(appUserRepository);
		verifyNoInteractions(eventStatusHistoryRepository);
		verifyNoInteractions(eventConverter);
	}

	@Test
	void updateEventStatusThrowsInvalidTransitionWhenGoingBackwards() {
		Long eventId = 1L;
		EventStatusUpdateRequest request = new EventStatusUpdateRequest(EventStatus.SCHEDULED, null);
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.OPEN);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventStatus(eventId, request)
		);

		assertSame(EventErrorCode.INVALID_EVENT_STATUS_TRANSITION, exception.getErrorCode());
		verifyNoInteractions(appUserRepository);
		verifyNoInteractions(eventStatusHistoryRepository);
		verifyNoInteractions(eventConverter);
	}

	@Test
	void updateEventStatusThrowsInvalidTransitionWhenEventIsAlreadyClosed() {
		Long eventId = 1L;
		EventStatusUpdateRequest request = new EventStatusUpdateRequest(EventStatus.OPEN, null);
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.CLOSED);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventStatus(eventId, request)
		);

		assertSame(EventErrorCode.INVALID_EVENT_STATUS_TRANSITION, exception.getErrorCode());
		verifyNoInteractions(appUserRepository);
		verifyNoInteractions(eventStatusHistoryRepository);
		verifyNoInteractions(eventConverter);
	}

	@Test
	void updateEventStatusThrowsConflictWhenStatusWasChangedConcurrently() {
		Long eventId = 1L;
		EventStatusUpdateRequest request = new EventStatusUpdateRequest(EventStatus.OPEN, "선착순 마감으로 오픈");
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.SCHEDULED);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

		AppUser admin = mock(AppUser.class);
		when(appUserRepository.findFirstByRoleAndStatusOrderByUserIdAsc(UserRole.ROLE_ADMIN, UserStatus.ACTIVE))
				.thenReturn(Optional.of(admin));
		when(eventRepository.updateStatusIfMatches(eventId, EventStatus.SCHEDULED, EventStatus.OPEN))
				.thenReturn(0);

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventStatus(eventId, request)
		);

		assertSame(EventErrorCode.EVENT_STATUS_CONFLICT, exception.getErrorCode());
		verify(event, never()).updateStatus(any());
		verifyNoInteractions(eventStatusHistoryRepository);
		verifyNoInteractions(eventConverter);
	}

	@Test
	void updateEventStatusThrowsNotFoundWhenEventDoesNotExist() {
		Long eventId = 99L;
		EventStatusUpdateRequest request = new EventStatusUpdateRequest(EventStatus.OPEN, null);
		when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.updateEventStatus(eventId, request)
		);

		assertSame(EventErrorCode.EVENT_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(appUserRepository);
		verifyNoInteractions(eventStatusHistoryRepository);
		verifyNoInteractions(eventConverter);
	}
}
