package com.mycom.petcoupon.event.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.res.PublicEventDetailResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class PublicEventDetailServiceTest {

	@Mock
	private EventRepository eventRepository;

	@Mock
	private EventStatusHistoryRepository eventStatusHistoryRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private CouponRepository couponRepository;

	@Mock
	private EventConverter eventConverter;

	@InjectMocks
	private EventServiceImpl eventService;

	@Test
	void getPublicEventDetailReturnsOpenEventWithItsCoupons() {
		Long eventId = 1L;
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.OPEN);
		List<Coupon> coupons = List.of(mock(Coupon.class), mock(Coupon.class));
		PublicEventDetailResponse expected = PublicEventDetailResponse.builder()
				.eventId(eventId)
				.name("여름 이벤트")
				.status(EventStatus.OPEN)
				.build();

		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
		when(couponRepository.findAllByEventId(eventId)).thenReturn(coupons);
		when(eventConverter.toPublicEventDetailResponse(event, coupons)).thenReturn(expected);

		PublicEventDetailResponse actual = eventService.getPublicEventDetail(eventId);

		assertSame(expected, actual);
		verify(couponRepository).findAllByEventId(eventId);
	}

	@Test
	void getPublicEventDetailReturnsEmptyCouponsWhenEventHasNoCoupon() {
		Long eventId = 1L;
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.OPEN);
		PublicEventDetailResponse expected = PublicEventDetailResponse.builder()
				.eventId(eventId)
				.coupons(List.of())
				.build();

		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
		when(couponRepository.findAllByEventId(eventId)).thenReturn(List.of());
		when(eventConverter.toPublicEventDetailResponse(event, List.of())).thenReturn(expected);

		PublicEventDetailResponse actual = eventService.getPublicEventDetail(eventId);

		assertSame(expected, actual);
	}

	@Test
	void getPublicEventDetailThrowsNotFoundWhenEventDoesNotExist() {
		Long eventId = 99L;
		when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.getPublicEventDetail(eventId)
		);

		assertSame(EventErrorCode.EVENT_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(couponRepository, eventConverter);
	}

	// SCHEDULED(미공개), CLOSED(종료) 모두 공개 상세에서는 EVENT_NOT_OPEN으로 막고,
	// 쿠폰 조회나 DTO 변환까지 가지 않는다.
	@ParameterizedTest
	@EnumSource(value = EventStatus.class, names = {"SCHEDULED", "CLOSED"})
	void getPublicEventDetailBlocksNonOpenEvent(EventStatus status) {
		Long eventId = 1L;
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(status);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> eventService.getPublicEventDetail(eventId)
		);

		assertSame(EventErrorCode.EVENT_NOT_OPEN, exception.getErrorCode());
		verifyNoInteractions(couponRepository, eventConverter);
	}

	@Test
	void getPublicEventDetailDoesNotTouchAdminOnlyCollaborators() {
		Long eventId = 1L;
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.OPEN);
		when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
		when(couponRepository.findAllByEventId(eventId)).thenReturn(List.of());
		when(eventConverter.toPublicEventDetailResponse(event, List.of()))
				.thenReturn(PublicEventDetailResponse.builder().eventId(eventId).build());

		eventService.getPublicEventDetail(eventId);

		verifyNoInteractions(eventStatusHistoryRepository, appUserRepository);
	}
}
