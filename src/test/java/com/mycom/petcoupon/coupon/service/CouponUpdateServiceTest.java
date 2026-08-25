package com.mycom.petcoupon.coupon.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponUpdateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponUpdateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@ExtendWith(MockitoExtension.class)
class CouponUpdateServiceTest {

	private static final Long EVENT_ID = 1L;
	private static final Long COUPON_ID = 10L;
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
	void updateCouponAppliesOnlyProvidedFieldsAndKeepsExistingOnes() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder()
				.name("가을 정률 쿠폰")
				.discountValue(30)
				.build();
		CouponUpdateResponse expected = CouponUpdateResponse.builder().couponId(COUPON_ID).build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponConverter.toUpdateResponse(coupon, couponStock)).thenReturn(expected);

		CouponUpdateResponse actual = couponService.updateCoupon(EVENT_ID, COUPON_ID, request);

		assertSame(expected, actual);
		assertEquals("가을 정률 쿠폰", coupon.getName());
		assertEquals(30, coupon.getDiscountValue());
		assertSame(DiscountType.RATE, coupon.getDiscountType());
		assertEquals(30_000, coupon.getMinOrderAmount());
		assertEquals(100, couponStock.getTotalQuantity());
	}

	@Test
	void updateCouponUpdatesTotalQuantityAndRemainingQuantityWhenIssuedQuantityIsZero() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder().totalQuantity(200).build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponConverter.toUpdateResponse(coupon, couponStock))
				.thenReturn(CouponUpdateResponse.builder().couponId(COUPON_ID).build());

		couponService.updateCoupon(EVENT_ID, COUPON_ID, request);

		assertEquals(200, couponStock.getTotalQuantity());
		assertEquals(200, couponStock.getRemainingQuantity());
	}

	@Test
	void updateCouponThrowsTotalQuantityUpdateNotAllowedWhenIssuedQuantityIsNonZero() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = mock(CouponStock.class);
		when(couponStock.getIssuedQuantity()).thenReturn(5);
		CouponUpdateRequest request = CouponUpdateRequest.builder().totalQuantity(200).build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.TOTAL_QUANTITY_UPDATE_NOT_ALLOWED, exception.getErrorCode());
		verify(couponStock, never()).updateTotalQuantity(any(Integer.class));
		verifyNoInteractions(couponConverter);
	}

	@Test
	void updateCouponThrowsEmptyUpdateRequestWhenAllFieldsAreNull() {
		CouponUpdateRequest request = CouponUpdateRequest.builder().build();

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.EMPTY_UPDATE_REQUEST, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@Test
	void updateCouponThrowsCouponNotFoundWhenCouponDoesNotExist() {
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 쿠폰").build();
		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.COUPON_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(couponStockRepository, couponConverter);
	}

	@Test
	void updateCouponThrowsCouponNotFoundWhenCouponBelongsToDifferentEvent() {
		Coupon coupon = mock(Coupon.class);
		Event event = mock(Event.class);
		when(coupon.getEvent()).thenReturn(event);
		when(event.getEventId()).thenReturn(2L);
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 쿠폰").build();
		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.COUPON_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(couponStockRepository, couponConverter);
	}

	@Test
	void updateCouponThrowsInvalidEventStatusForUpdateWhenEventIsNotScheduled() {
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(EventStatus.OPEN);
		Coupon coupon = mock(Coupon.class);
		when(coupon.getEvent()).thenReturn(event);
		when(event.getEventId()).thenReturn(EVENT_ID);
		CouponStock couponStock = mock(CouponStock.class);
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 쿠폰").build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_EVENT_STATUS_FOR_UPDATE, exception.getErrorCode());
		verifyNoInteractions(couponConverter);
	}

	@Test
	void updateCouponThrowsInvalidCouponStatusForUpdateWhenCouponIsNotReady() {
		Event event = scheduledEvent();
		Coupon coupon = activeRateCoupon(event);
		CouponStock couponStock = mock(CouponStock.class);
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 쿠폰").build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_COUPON_STATUS_FOR_UPDATE, exception.getErrorCode());
		verifyNoInteractions(couponConverter);
	}

	@Test
	void updateCouponThrowsInvalidIssuePeriodWhenMergedEndIsBeforeStart() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = mock(CouponStock.class);
		CouponUpdateRequest request = CouponUpdateRequest.builder()
				.issueEndAt(coupon.getIssueStartAt().minusSeconds(1))
				.build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_ISSUE_PERIOD, exception.getErrorCode());
		verifyNoInteractions(couponConverter);
	}

	@Test
	void updateCouponThrowsIssuePeriodOutOfEventPeriodWhenMergedPeriodExceedsEvent() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = mock(CouponStock.class);
		CouponUpdateRequest request = CouponUpdateRequest.builder()
				.issueEndAt(EVENT_CLOSE_AT.plusSeconds(1))
				.build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_PERIOD_OUT_OF_EVENT_PERIOD, exception.getErrorCode());
		verifyNoInteractions(couponConverter);
	}

	@Test
	void updateCouponThrowsInvalidRateDiscountPolicyWhenMergedDiscountExceedsOneHundred() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = mock(CouponStock.class);
		CouponUpdateRequest request = CouponUpdateRequest.builder().discountValue(101).build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_RATE_DISCOUNT_POLICY, exception.getErrorCode());
		verifyNoInteractions(couponConverter);
	}

	@Test
	void updateCouponClearsMaxDiscountAmountWhenSwitchingToFixedAmount() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder()
				.discountType(DiscountType.FIXED_AMOUNT)
				.build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponConverter.toUpdateResponse(coupon, couponStock))
				.thenReturn(CouponUpdateResponse.builder().couponId(COUPON_ID).build());

		couponService.updateCoupon(EVENT_ID, COUPON_ID, request);

		assertSame(DiscountType.FIXED_AMOUNT, coupon.getDiscountType());
		assertNull(coupon.getMaxDiscountAmount());
	}

	// 기존값에서 넘어온 maxDiscountAmount는 조용히 버리지만(위 테스트), 요청에 직접 실려 온 값은
	// 성립할 수 없는 정책이므로 거부한다 — 생성 API가 같은 조합에 400-5를 던지는 것과 맞춘다.
	@Test
	void updateCouponThrowsInvalidFixedAmountDiscountPolicyWhenMaxDiscountAmountIsRequested() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = mock(CouponStock.class);
		CouponUpdateRequest request = CouponUpdateRequest.builder()
				.discountType(DiscountType.FIXED_AMOUNT)
				.maxDiscountAmount(5_000)
				.build();

		when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_FIXED_AMOUNT_DISCOUNT_POLICY, exception.getErrorCode());
		verifyNoInteractions(couponConverter);
	}

	private Event scheduledEvent() {
		Event event = mock(Event.class);
		when(event.getEventId()).thenReturn(EVENT_ID);
		when(event.getStatus()).thenReturn(EventStatus.SCHEDULED);
		lenient().when(event.getOpenAt()).thenReturn(EVENT_OPEN_AT);
		lenient().when(event.getCloseAt()).thenReturn(EVENT_CLOSE_AT);
		return event;
	}

	private Coupon readyRateCoupon(Event event) {
		return Coupon.builder()
				.event(event)
				.name("여름 정률 쿠폰")
				.discountType(DiscountType.RATE)
				.discountValue(20)
				.minOrderAmount(30_000)
				.maxDiscountAmount(10_000)
				.issueStartAt(EVENT_OPEN_AT.plusDays(1))
				.issueEndAt(EVENT_CLOSE_AT.minusDays(1))
				.validDays(7)
				.build();
	}

	private Coupon activeRateCoupon(Event event) {
		Coupon coupon = mock(Coupon.class);
		when(coupon.getEvent()).thenReturn(event);
		when(coupon.getStatus()).thenReturn(com.mycom.petcoupon.coupon.entity.enums.CouponStatus.ACTIVE);
		return coupon;
	}

	private CouponStock couponStock(Coupon coupon, int totalQuantity) {
		return CouponStock.builder()
				.coupon(coupon)
				.totalQuantity(totalQuantity)
				.build();
	}
}
