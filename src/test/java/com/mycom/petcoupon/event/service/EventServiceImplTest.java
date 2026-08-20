package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.EventStatusHistory;
import com.mycom.petcoupon.event.entity.enums.ActorType;
import com.mycom.petcoupon.event.entity.enums.EventHistoryStatus;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.code.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.entity.enums.UserStatus;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class EventServiceImplTest {

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
	void createEventSavesInitialStatusHistory() {
		EventCreateRequest request = new EventCreateRequest(
				"반려동물 여름 이벤트",
				"선착순 쿠폰 이벤트",
				LocalDateTime.of(2026, 8, 20, 9, 0),
				LocalDateTime.of(2026, 8, 31, 23, 59)
		);
		AppUser createdBy = mock(AppUser.class);
		Event event = mock(Event.class);
		Event savedEvent = mock(Event.class);
		EventCreateResponse expected = EventCreateResponse.builder()
				.eventId(1L)
				.status(EventStatus.SCHEDULED)
				.build();

		when(appUserRepository.findFirstByRoleAndStatusOrderByUserIdAsc(
				UserRole.ROLE_ADMIN,
				UserStatus.ACTIVE
		)).thenReturn(Optional.of(createdBy));
		when(createdBy.getUserId()).thenReturn(7L);
		when(eventConverter.toEntity(request, createdBy)).thenReturn(event);
		when(eventRepository.save(event)).thenReturn(savedEvent);
		when(eventConverter.toCreateResponse(savedEvent)).thenReturn(expected);

		EventCreateResponse actual = eventService.createEvent(request);

		ArgumentCaptor<EventStatusHistory> historyCaptor = ArgumentCaptor.forClass(EventStatusHistory.class);
		verify(eventStatusHistoryRepository).save(historyCaptor.capture());
		EventStatusHistory savedHistory = historyCaptor.getValue();
		assertSame(savedEvent, savedHistory.getEvent());
		assertSame(EventHistoryStatus.NONE, savedHistory.getFromStatus());
		assertSame(EventHistoryStatus.SCHEDULED, savedHistory.getToStatus());
		assertSame(ActorType.ADMIN, savedHistory.getActorType());
		assertEquals(7L, savedHistory.getActorId());
		assertEquals("이벤트 생성", savedHistory.getReason());
		assertSame(expected, actual);
	}

	@Test
	void createEventThrowsInvalidEventPeriodWhenCloseAtEqualsOpenAt() {
		LocalDateTime openAt = LocalDateTime.of(2026, 8, 20, 9, 0);
		EventCreateRequest request = new EventCreateRequest(
				"반려동물 여름 이벤트",
				"선착순 쿠폰 이벤트",
				openAt,
				openAt
		);

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.createEvent(request)
		);

		assertSame(EventErrorCode.INVALID_EVENT_PERIOD, exception.getErrorCode());
		verifyNoInteractions(
				eventRepository,
				eventStatusHistoryRepository,
				appUserRepository,
				eventConverter
		);
	}

	@Test
	void createEventThrowsInvalidEventPeriodWhenCloseAtIsBeforeOpenAt() {
		EventCreateRequest request = new EventCreateRequest(
				"반려동물 여름 이벤트",
				"선착순 쿠폰 이벤트",
				LocalDateTime.of(2026, 8, 20, 9, 0),
				LocalDateTime.of(2026, 8, 20, 8, 59)
		);

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.createEvent(request)
		);

		assertSame(EventErrorCode.INVALID_EVENT_PERIOD, exception.getErrorCode());
		verifyNoInteractions(
				eventRepository,
				eventStatusHistoryRepository,
				appUserRepository,
				eventConverter
		);
	}

	@Test
	void createEventThrowsInternalServerErrorWhenActiveAdminDoesNotExist() {
		EventCreateRequest request = new EventCreateRequest(
				"반려동물 여름 이벤트",
				"선착순 쿠폰 이벤트",
				LocalDateTime.of(2026, 8, 20, 9, 0),
				LocalDateTime.of(2026, 8, 31, 23, 59)
		);
		when(appUserRepository.findFirstByRoleAndStatusOrderByUserIdAsc(
				UserRole.ROLE_ADMIN,
				UserStatus.ACTIVE
		)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.createEvent(request)
		);

		assertSame(CommonErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		verifyNoInteractions(eventRepository, eventStatusHistoryRepository, eventConverter);
	}

}
