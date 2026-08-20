package com.mycom.petcoupon.coupon.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
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

	@ParameterizedTest(name = "{0}")
	@MethodSource("validDiscountPolicies")
	void createCouponSavesCouponAndInitialStockForValidPolicy(
			String description,
			CouponCreateRequest request
	) {
		Event event = scheduledEvent();
		Coupon coupon = mock(Coupon.class);
		Coupon savedCoupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		CouponStock savedCouponStock = mock(CouponStock.class);
		CouponCreateResponse expected = mock(CouponCreateResponse.class);

		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
		when(couponConverter.toCoupon(event, request)).thenReturn(coupon);
		when(couponRepository.save(coupon)).thenReturn(savedCoupon);
		when(couponConverter.toCouponStock(savedCoupon, request)).thenReturn(couponStock);
		when(couponStockRepository.save(couponStock)).thenReturn(savedCouponStock);
		when(couponConverter.toCreateResponse(savedCoupon, savedCouponStock)).thenReturn(expected);

		CouponCreateResponse actual = couponService.createCoupon(EVENT_ID, request);

		assertSame(expected, actual);
		InOrder persistenceOrder = inOrder(couponRepository, couponStockRepository);
		persistenceOrder.verify(couponRepository).save(coupon);
		persistenceOrder.verify(couponStockRepository).save(couponStock);
	}

	@Test
	void createCouponThrowsNotFoundWhenEventDoesNotExist() {
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, fixedAmountRequest(EVENT_OPEN_AT, EVENT_CLOSE_AT))
		);

		assertSame(CommonErrorCode.NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@ParameterizedTest
	@EnumSource(value = EventStatus.class, names = "SCHEDULED", mode = EnumSource.Mode.EXCLUDE)
	void createCouponThrowsBadRequestWhenEventIsNotScheduled(EventStatus eventStatus) {
		Event event = mock(Event.class);
		when(event.getStatus()).thenReturn(eventStatus);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, fixedAmountRequest(EVENT_OPEN_AT, EVENT_CLOSE_AT))
		);

		assertSame(CommonErrorCode.BAD_REQUEST, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidIssuePeriods")
	void createCouponThrowsBadRequestWhenIssuePeriodIsInvalid(
			String description,
			CouponCreateRequest request
	) {
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(scheduledEvent()));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CommonErrorCode.BAD_REQUEST, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidDiscountPolicies")
	void createCouponThrowsBadRequestWhenDiscountPolicyIsInvalid(
			String description,
			CouponCreateRequest request
	) {
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(scheduledEvent()));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CommonErrorCode.BAD_REQUEST, exception.getErrorCode());
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter);
	}

	private static Stream<Arguments> validDiscountPolicies() {
		return Stream.of(
				Arguments.of(
						"정액 할인",
						request(DiscountType.FIXED_AMOUNT, 5_000, null, EVENT_OPEN_AT, EVENT_CLOSE_AT)
				),
				Arguments.of(
						"최대 할인 금액이 없는 정률 할인",
						request(DiscountType.RATE, 10, null, EVENT_OPEN_AT, EVENT_CLOSE_AT)
				),
				Arguments.of(
						"100%이며 최대 할인 금액이 있는 정률 할인",
						request(DiscountType.RATE, 100, 20_000, EVENT_OPEN_AT, EVENT_CLOSE_AT)
				)
		);
	}

	private static Stream<Arguments> invalidIssuePeriods() {
		return Stream.of(
				Arguments.of(
						"발급 시작과 종료 시각이 같음",
						fixedAmountRequest(EVENT_OPEN_AT, EVENT_OPEN_AT)
				),
				Arguments.of(
						"발급 종료가 시작보다 빠름",
						fixedAmountRequest(EVENT_OPEN_AT.plusHours(1), EVENT_OPEN_AT)
				),
				Arguments.of(
						"발급 시작이 이벤트 시작보다 빠름",
						fixedAmountRequest(EVENT_OPEN_AT.minusSeconds(1), EVENT_CLOSE_AT)
				),
				Arguments.of(
						"발급 종료가 이벤트 종료보다 늦음",
						fixedAmountRequest(EVENT_OPEN_AT, EVENT_CLOSE_AT.plusSeconds(1))
				)
		);
	}

	private static Stream<Arguments> invalidDiscountPolicies() {
		return Stream.of(
				Arguments.of(
						"정률 할인이 100%를 초과함",
						request(DiscountType.RATE, 101, null, EVENT_OPEN_AT, EVENT_CLOSE_AT)
				),
				Arguments.of(
						"정률 할인 최대 금액이 0임",
						request(DiscountType.RATE, 10, 0, EVENT_OPEN_AT, EVENT_CLOSE_AT)
				),
				Arguments.of(
						"정률 할인 최대 금액이 음수임",
						request(DiscountType.RATE, 10, -1, EVENT_OPEN_AT, EVENT_CLOSE_AT)
				),
				Arguments.of(
						"정액 할인에 최대 할인 금액이 지정됨",
						request(DiscountType.FIXED_AMOUNT, 5_000, 20_000, EVENT_OPEN_AT, EVENT_CLOSE_AT)
				)
		);
	}

	private static Event scheduledEvent() {
		return Event.builder()
				.name("반려동물 여름 이벤트")
				.description("선착순 쿠폰 이벤트")
				.openAt(EVENT_OPEN_AT)
				.closeAt(EVENT_CLOSE_AT)
				.build();
	}

	private static CouponCreateRequest fixedAmountRequest(
			LocalDateTime issueStartAt,
			LocalDateTime issueEndAt
	) {
		return request(DiscountType.FIXED_AMOUNT, 5_000, null, issueStartAt, issueEndAt);
	}

	private static CouponCreateRequest request(
			DiscountType discountType,
			Integer discountValue,
			Integer maxDiscountAmount,
			LocalDateTime issueStartAt,
			LocalDateTime issueEndAt
	) {
		return new CouponCreateRequest(
				"여름 쿠폰",
				discountType,
				discountValue,
				10_000,
				maxDiscountAmount,
				issueStartAt,
				issueEndAt,
				7,
				100
		);
	}
}
