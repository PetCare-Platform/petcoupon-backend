package com.mycom.petcoupon.coupon.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@ExtendWith(MockitoExtension.class)
class CouponCreateServiceTest {

	private static final Long EVENT_ID = 1L;
	// [#222] issueStartAt이 과거면 거부하는 검증이 생겨서, 고정 날짜 대신 NOW 기준 상대
	// 시각을 쓴다(CouponUpdateServiceTest와 동일 패턴). NOW는 findDatabaseNow() 스텁 값이기도 하다.
	private static final LocalDateTime NOW = LocalDateTime.now();
	private static final LocalDateTime EVENT_OPEN_AT = NOW.plusDays(1);
	private static final LocalDateTime EVENT_CLOSE_AT = NOW.plusDays(12);

	@Mock
	private EventRepository eventRepository;

	@Mock
	private CouponRepository couponRepository;

	@Mock
	private CouponStockRepository couponStockRepository;

	@Mock
	private CouponConverter couponConverter;

	@Mock
	private CouponIssueLuaService couponIssueLuaService;

	@InjectMocks
	private CouponServiceImpl couponService;

	// 상태 검증에서 먼저 걸러지는 테스트는 여기까지 오지 않으므로 lenient로 둔다.
	@BeforeEach
	void stubDatabaseNow() {
		lenient().when(couponRepository.findDatabaseNow()).thenReturn(NOW);
	}

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
		when(savedCoupon.getCouponId()).thenReturn(10L);
		when(savedCouponStock.getTotalQuantity()).thenReturn(100);
		when(couponIssueLuaService.resetIssueState(10L, 100)).thenReturn(100);
		when(couponConverter.toCreateResponse(savedCoupon, savedCouponStock)).thenReturn(expected);

		CouponCreateResponse actual = couponService.createCoupon(EVENT_ID, request);

		assertSame(expected, actual);
		verify(couponRepository).save(coupon);
		verify(couponStockRepository).save(couponStock);
		verify(couponIssueLuaService).resetIssueState(10L, 100);
	}

	// DB에 쿠폰·재고를 저장한 뒤에 Redis 재고 초기화가 이어져야 한다. 순서가 뒤집히면
	// 초기화한 재고를 이후 저장이 덮거나, 저장이 실패해도 Redis에 유령 재고가 남는다.
	@Test
	void createCouponInitializesRedisStockAfterPersistingCouponAndStock() {
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

		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
		when(couponConverter.toCoupon(event, request)).thenReturn(coupon);
		when(couponRepository.save(coupon)).thenReturn(savedCoupon);
		when(couponConverter.toCouponStock(savedCoupon, request)).thenReturn(couponStock);
		when(couponStockRepository.save(couponStock)).thenReturn(savedCouponStock);
		when(savedCoupon.getCouponId()).thenReturn(10L);
		when(savedCouponStock.getTotalQuantity()).thenReturn(100);
		when(couponIssueLuaService.resetIssueState(10L, 100)).thenReturn(100);
		when(couponConverter.toCreateResponse(savedCoupon, savedCouponStock))
				.thenReturn(CouponCreateResponse.builder().couponId(10L).build());

		couponService.createCoupon(EVENT_ID, request);

		InOrder inOrder = inOrder(couponRepository, couponStockRepository, couponIssueLuaService);
		inOrder.verify(couponRepository).save(coupon);
		inOrder.verify(couponStockRepository).save(couponStock);
		inOrder.verify(couponIssueLuaService).resetIssueState(10L, 100);
	}

	// Redis에 세팅한 재고가 되읽히지 않으면(null) 초기화가 끝나지 않은 것이므로 생성을 실패시킨다.
	@Test
	void createCouponThrowsWhenRedisStockIsNotReadBack() {
		Event event = scheduledEvent();
		CouponCreateRequest request = validRateRequest();
		Coupon coupon = mock(Coupon.class);
		Coupon savedCoupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		CouponStock savedCouponStock = mock(CouponStock.class);

		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
		when(couponConverter.toCoupon(event, request)).thenReturn(coupon);
		when(couponRepository.save(coupon)).thenReturn(savedCoupon);
		when(couponConverter.toCouponStock(savedCoupon, request)).thenReturn(couponStock);
		when(couponStockRepository.save(couponStock)).thenReturn(savedCouponStock);
		when(savedCoupon.getCouponId()).thenReturn(10L);
		when(savedCouponStock.getTotalQuantity()).thenReturn(100);
		when(couponIssueLuaService.resetIssueState(10L, 100)).thenReturn(null);

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_REDIS_STATE_CLEAR_FAILED, exception.getErrorCode());
		verify(couponConverter, never()).toCreateResponse(savedCoupon, savedCouponStock);
	}

	// 되읽은 재고가 세팅한 수량과 다르면 동시 변경·상태 오염이므로 생성을 실패시킨다.
	@Test
	void createCouponThrowsWhenRedisStockDiffersFromTotalQuantity() {
		Event event = scheduledEvent();
		CouponCreateRequest request = validRateRequest();
		Coupon coupon = mock(Coupon.class);
		Coupon savedCoupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		CouponStock savedCouponStock = mock(CouponStock.class);

		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
		when(couponConverter.toCoupon(event, request)).thenReturn(coupon);
		when(couponRepository.save(coupon)).thenReturn(savedCoupon);
		when(couponConverter.toCouponStock(savedCoupon, request)).thenReturn(couponStock);
		when(couponStockRepository.save(couponStock)).thenReturn(savedCouponStock);
		when(savedCoupon.getCouponId()).thenReturn(10L);
		when(savedCouponStock.getTotalQuantity()).thenReturn(100);
		when(couponIssueLuaService.resetIssueState(10L, 100)).thenReturn(99);

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_REDIS_STATE_CLEAR_FAILED, exception.getErrorCode());
	}

	// Redis 초기화가 예외로 실패하면 그대로 전파돼 트랜잭션이 롤백된다.
	@Test
	void createCouponPropagatesWhenRedisInitializationFails() {
		Event event = scheduledEvent();
		CouponCreateRequest request = validRateRequest();
		Coupon coupon = mock(Coupon.class);
		Coupon savedCoupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		CouponStock savedCouponStock = mock(CouponStock.class);

		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
		when(couponConverter.toCoupon(event, request)).thenReturn(coupon);
		when(couponRepository.save(coupon)).thenReturn(savedCoupon);
		when(couponConverter.toCouponStock(savedCoupon, request)).thenReturn(couponStock);
		when(couponStockRepository.save(couponStock)).thenReturn(savedCouponStock);
		when(savedCoupon.getCouponId()).thenReturn(10L);
		when(savedCouponStock.getTotalQuantity()).thenReturn(100);
		when(couponIssueLuaService.resetIssueState(10L, 100))
				.thenThrow(new GeneralException(CouponErrorCode.ISSUE_REDIS_STATE_CLEAR_FAILED));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_REDIS_STATE_CLEAR_FAILED, exception.getErrorCode());
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
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponRepository, couponStockRepository, couponConverter, couponIssueLuaService);
	}

	// [#222] issueStartAt이 과거면 거부한다. 이벤트 기간 검증이 먼저 걸리지 않도록, 공용
	// EVENT_OPEN_AT(미래)이 아니라 이미 시작된 이벤트를 별도로 써서 "기간 안이면서 과거"인
	// issueStartAt을 만든다.
	@Test
	void createCouponThrowsIssueStartAtInPastWhenStartAtIsBeforeNow() {
		Event alreadyOpenEvent = Event.builder()
				.name("이미 시작된 이벤트")
				.openAt(NOW.minusDays(5))
				.closeAt(NOW.plusDays(5))
				.build();
		CouponCreateRequest request = rateRequest(
				NOW.minusDays(1),
				NOW.plusDays(1),
				20,
				10_000
		);
		when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(alreadyOpenEvent));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.createCoupon(EVENT_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_START_AT_IN_PAST, exception.getErrorCode());
		verifyNoInteractions(couponStockRepository, couponConverter, couponIssueLuaService);
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
		// [#222] validateIssuePeriod가 findDatabaseNow()를 먼저 호출하므로 couponRepository는 제외
		verifyNoInteractions(couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponStockRepository, couponConverter, couponIssueLuaService);
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
		verifyNoInteractions(couponStockRepository, couponConverter, couponIssueLuaService);
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
		when(savedCoupon.getCouponId()).thenReturn(10L);
		when(savedCouponStock.getTotalQuantity()).thenReturn(100);
		when(couponIssueLuaService.resetIssueState(10L, 100)).thenReturn(100);
		when(couponConverter.toCreateResponse(savedCoupon, savedCouponStock)).thenReturn(response);

		return response;
	}
}
