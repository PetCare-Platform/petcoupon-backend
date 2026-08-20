package com.mycom.petcoupon.coupon.service;

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

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.exception.code.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.code.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@ExtendWith(MockitoExtension.class)
class CouponCreateServiceTest {

	private static final Long EVENT_ID = 1L;
	private static final LocalDateTime EVENT_OPEN_AT = LocalDateTime.of(2026, 8, 20, 9, 0);
	private static final LocalDateTime EVENT_CLOSE_AT = LocalDateTime.of(2026, 8, 31, 23, 59);

	@Mock
	private EventRepository eventRepository;

	@Mock
	private CouponRepository couponRepository;

	@Mock
	private CouponStockRepository couponStockRepository;

	@Mock
	private CouponConverter couponConverter;

	@InjectMocks
	private CouponServiceImpl couponService;

	@Test
	void createCouponSavesCouponAndStock() {
		Event event = scheduledEvent();
		CouponCreateRequest request = rateRequest(
				EVENT_OPEN_AT.plusDays(1),
				EVENT_CLOSE_AT.minusDays(1),
				20,
				10_000
		);
		Coupon coupon = mock(Coupon.class);
		Coupon savedCoupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		CouponStock savedCouponStock = mock(CouponStock.class);
		CouponCreateResponse expected = CouponCreateResponse.builder()
				.couponId(10L)
				.eventId(EVENT_ID)
				.build();

		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
		when(couponConverter.toCoupon(event, request)).thenReturn(coupon);
		when(couponRepository.save(coupon)).thenReturn(savedCoupon);
		when(couponConverter.toCouponStock(savedCoupon, request)).thenReturn(couponStock);
		when(couponStockRepository.save(couponStock)).thenReturn(savedCouponStock);
		when(couponConverter.toCreateResponse(savedCoupon, savedCouponStock)).thenReturn(expected);

		CouponCreateResponse actual = couponService.createCoupon(EVENT_ID, request);

		assertSame(expected, actual);
		verify(couponRepository).save(coupon);
		verify(couponStockRepository).save(couponStock);
	}

	@Test
	void createCouponThrowsEventNotFoundWhenEventDoesNotExist() {
		CouponCreateRequest request = validRateRequest();
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(EventErrorCode.EVENT_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsInvalidEventStatusWhenEventIsOpen() {
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.OPEN);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, validRateRequest())
		);

		assertSame(CouponErrorCode.INVALID_EVENT_STATUS, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsInvalidEventStatusWhenEventIsClosed() {
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.CLOSED);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, validRateRequest())
		);

		assertSame(CouponErrorCode.INVALID_EVENT_STATUS, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsInvalidIssuePeriodWhenEndEqualsStart() {
		Event event = scheduledEvent();
		LocalDateTime issueAt = EVENT_OPEN_AT.plusDays(1);
		CouponCreateRequest request = rateRequest(issueAt, issueAt, 20, 10_000);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_ISSUE_PERIOD, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsInvalidIssuePeriodWhenEndIsBeforeStart() {
		Event event = scheduledEvent();
		CouponCreateRequest request = rateRequest(
				EVENT_OPEN_AT.plusDays(2),
				EVENT_OPEN_AT.plusDays(1),
				20,
				10_000
		);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_ISSUE_PERIOD, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsIssuePeriodOutOfEventPeriodWhenStartIsTooEarly() {
		Event event = scheduledEvent();
		CouponCreateRequest request = rateRequest(
				EVENT_OPEN_AT.minusSeconds(1),
				EVENT_OPEN_AT.plusDays(1),
				20,
				10_000
		);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_PERIOD_OUT_OF_EVENT_PERIOD, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsIssuePeriodOutOfEventPeriodWhenEndIsTooLate() {
		Event event = scheduledEvent();
		CouponCreateRequest request = rateRequest(
				EVENT_OPEN_AT.plusDays(1),
				EVENT_CLOSE_AT.plusSeconds(1),
				20,
				10_000
		);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_PERIOD_OUT_OF_EVENT_PERIOD, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponAcceptsIssuePeriodEqualToEventPeriod() {
		Event event = scheduledEvent();
		CouponCreateRequest request = rateRequest(EVENT_OPEN_AT, EVENT_CLOSE_AT, 20, 10_000);
		CouponCreateResponse expected = stubSuccessfulCreation(event, request);

		CouponCreateResponse actual = couponService.createCoupon(EVENT_ID, request);

		assertSame(expected, actual);
	}

	@Test
	void createCouponThrowsInvalidRateDiscountPolicyWhenDiscountExceedsOneHundred() {
		Event event = scheduledEvent();
		CouponCreateRequest request = rateRequest(
				EVENT_OPEN_AT.plusDays(1),
				EVENT_CLOSE_AT.minusDays(1),
				101,
				10_000
		);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_RATE_DISCOUNT_POLICY, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsInvalidRateDiscountPolicyWhenMaxDiscountIsZero() {
		Event event = scheduledEvent();
		CouponCreateRequest request = rateRequest(
				EVENT_OPEN_AT.plusDays(1),
				EVENT_CLOSE_AT.minusDays(1),
				20,
				0
		);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_RATE_DISCOUNT_POLICY, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsInvalidRateDiscountPolicyWhenMaxDiscountIsNegative() {
		Event event = scheduledEvent();
		CouponCreateRequest request = rateRequest(
				EVENT_OPEN_AT.plusDays(1),
				EVENT_CLOSE_AT.minusDays(1),
				20,
				-1
		);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_RATE_DISCOUNT_POLICY, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponThrowsInvalidFixedAmountPolicyWhenMaxDiscountExists() {
		Event event = scheduledEvent();
		CouponCreateRequest request = fixedAmountRequest(10_000);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_FIXED_AMOUNT_DISCOUNT_POLICY, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void createCouponAcceptsFixedAmountPolicyWithoutMaxDiscount() {
		Event event = scheduledEvent();
		CouponCreateRequest request = fixedAmountRequest(null);
		CouponCreateResponse expected = stubSuccessfulCreation(event, request);

		CouponCreateResponse actual = couponService.createCoupon(EVENT_ID, request);

		assertSame(expected, actual);
	}

	private Event scheduledEvent() {
		return Event.builder()
				.name("반려동물 여름 이벤트")
				.openAt(EVENT_OPEN_AT)
				.closeAt(EVENT_CLOSE_AT)
				.build();
	}

	private CouponCreateRequest validRateRequest() {
		return rateRequest(
				EVENT_OPEN_AT.plusDays(1),
				EVENT_CLOSE_AT.minusDays(1),
				20,
				10_000
		);
	}

	private CouponCreateRequest rateRequest(
			LocalDateTime issueStartAt,
			LocalDateTime issueEndAt,
			int discountValue,
			Integer maxDiscountAmount
	) {
		return CouponCreateRequest.builder()
				.name("여름 정률 쿠폰")
				.discountType(DiscountType.RATE)
				.discountValue(discountValue)
				.minOrderAmount(30_000)
				.maxDiscountAmount(maxDiscountAmount)
				.issueStartAt(issueStartAt)
				.issueEndAt(issueEndAt)
				.validDays(7)
				.totalQuantity(100)
				.build();
	}

	private CouponCreateRequest fixedAmountRequest(Integer maxDiscountAmount) {
		return CouponCreateRequest.builder()
				.name("여름 정액 쿠폰")
				.discountType(DiscountType.FIXED_AMOUNT)
				.discountValue(5_000)
				.minOrderAmount(30_000)
				.maxDiscountAmount(maxDiscountAmount)
				.issueStartAt(EVENT_OPEN_AT.plusDays(1))
				.issueEndAt(EVENT_CLOSE_AT.minusDays(1))
				.validDays(7)
				.totalQuantity(100)
				.build();
	}

	private CouponCreateResponse stubSuccessfulCreation(Event event, CouponCreateRequest request) {
		Coupon coupon = mock(Coupon.class);
		Coupon savedCoupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		CouponStock savedCouponStock = mock(CouponStock.class);
		CouponCreateResponse response = CouponCreateResponse.builder()
				.couponId(10L)
				.eventId(EVENT_ID)
				.build();

		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
		when(couponConverter.toCoupon(event, request)).thenReturn(coupon);
		when(couponRepository.save(coupon)).thenReturn(savedCoupon);
		when(couponConverter.toCouponStock(savedCoupon, request)).thenReturn(couponStock);
		when(couponStockRepository.save(couponStock)).thenReturn(savedCouponStock);
		when(couponConverter.toCreateResponse(savedCoupon, savedCouponStock)).thenReturn(response);

		return response;
	}
}
