package com.mycom.petcoupon.coupon.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
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
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
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
	// 수정 가능 조건에 issueStartAt > now가 들어가므로 고정 날짜를 쓰면 그 날이 지나는 순간
	// 테스트가 통째로 깨진다. NOW 하나를 기준으로 삼고 전부 상대 시각으로 잡는다.
	// NOW는 findDatabaseNow()의 스텁 값이기도 하다 — 프로덕션 코드가 DB 시각을 쓰기 때문.
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
	void updateCouponAppliesOnlyProvidedFieldsAndKeepsExistingOnes() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder()
				.name("가을 정률 쿠폰")
				.discountValue(30)
				.build();
		CouponUpdateResponse expected = CouponUpdateResponse.builder().couponId(COUPON_ID).build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponConverter.toUpdateResponse(coupon, couponStock)).thenReturn(expected);

		CouponUpdateResponse actual = couponService.updateCoupon(EVENT_ID, COUPON_ID, request);

		assertSame(expected, actual);
		assertEquals("가을 정률 쿠폰", coupon.getName());
		assertEquals(30, coupon.getDiscountValue());
		assertSame(DiscountType.RATE, coupon.getDiscountType());
		assertEquals(30_000, coupon.getMinOrderAmount());
		assertEquals(100, couponStock.getTotalQuantity());
	}

	// [PR 리뷰 반영] validateIssueNotStarted와 validateIssuePeriod가 각자 findDatabaseNow()를
	// 부르면, 같은 트랜잭션 안에서 now를 두 번 다른 시점에 읽게 되어 그 사이 issueStartAt을
	// 지나가버리는 레이스가 생긴다(issueStartAt을 안 건드린 요청도 스푸리어스하게 실패할 수
	// 있음). updateCoupon이 now를 한 번만 읽어서 두 검증에 공유하는지 호출 횟수로 확인한다.
	@Test
	void updateCoupon은_findDatabaseNow를_한_번만_호출한다() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 정률 쿠폰").build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponConverter.toUpdateResponse(coupon, couponStock))
				.thenReturn(CouponUpdateResponse.builder().couponId(COUPON_ID).build());

		couponService.updateCoupon(EVENT_ID, COUPON_ID, request);

		verify(couponRepository, times(1)).findDatabaseNow();
	}

	@Test
	void updateCouponUpdatesTotalQuantityAndRemainingQuantityWhenIssuedQuantityIsZero() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder().totalQuantity(200).build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponIssueLuaService.resetIssueState(COUPON_ID, 200)).thenReturn(200);
		when(couponConverter.toUpdateResponse(coupon, couponStock))
				.thenReturn(CouponUpdateResponse.builder().couponId(COUPON_ID).build());

		couponService.updateCoupon(EVENT_ID, COUPON_ID, request);

		assertEquals(200, couponStock.getTotalQuantity());
		assertEquals(200, couponStock.getRemainingQuantity());
		verify(couponIssueLuaService).resetIssueState(COUPON_ID, 200);
	}

	// 총수량이 바뀌면 DB 재고뿐 아니라 Redis 발급 재고 키도 새 수량으로 다시 세워야
	// GET /admin/coupons/{couponId}/status의 실시간 잔여가 어긋나지 않는다.
	@Test
	void updateCouponResetsRedisIssueStockWhenTotalQuantityChanges() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder().totalQuantity(200).build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponIssueLuaService.resetIssueState(COUPON_ID, 200)).thenReturn(200);
		when(couponConverter.toUpdateResponse(coupon, couponStock))
				.thenReturn(CouponUpdateResponse.builder().couponId(COUPON_ID).build());

		couponService.updateCoupon(EVENT_ID, COUPON_ID, request);

		verify(couponIssueLuaService).resetIssueState(COUPON_ID, 200);
	}

	// 총수량을 건드리지 않는 수정은 Redis 재고에 손대지 않는다.
	@Test
	void updateCouponDoesNotTouchRedisWhenTotalQuantityIsUnchanged() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 정률 쿠폰").build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponConverter.toUpdateResponse(coupon, couponStock))
				.thenReturn(CouponUpdateResponse.builder().couponId(COUPON_ID).build());

		couponService.updateCoupon(EVENT_ID, COUPON_ID, request);

		verifyNoInteractions(couponIssueLuaService);
	}

	// Redis에 세팅한 재고가 되읽히지 않으면(null) 초기화가 끝나지 않은 것이므로 수정을 롤백한다.
	@Test
	void updateCouponThrowsWhenRedisIssueStockIsNotReadBack() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = couponStock(coupon, 100);
		CouponUpdateRequest request = CouponUpdateRequest.builder().totalQuantity(200).build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));
		when(couponIssueLuaService.resetIssueState(COUPON_ID, 200)).thenReturn(null);

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_REDIS_STATE_CLEAR_FAILED, exception.getErrorCode());
	}

	@Test
	void updateCouponThrowsTotalQuantityUpdateNotAllowedWhenIssuedQuantityIsNonZero() {
		Event event = scheduledEvent();
		Coupon coupon = readyRateCoupon(event);
		CouponStock couponStock = mock(CouponStock.class);
		when(couponStock.getIssuedQuantity()).thenReturn(5);
		CouponUpdateRequest request = CouponUpdateRequest.builder().totalQuantity(200).build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.TOTAL_QUANTITY_UPDATE_NOT_ALLOWED, exception.getErrorCode());
		verify(couponStock, never()).updateTotalQuantity(any(Integer.class));
		verifyNoInteractions(couponConverter, couponIssueLuaService);
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
		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.empty());

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
		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));

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

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

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

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.INVALID_COUPON_STATUS_FOR_UPDATE, exception.getErrorCode());
		verifyNoInteractions(couponConverter);
	}

	// 스케줄러 지연 구간 재현: issueStartAt은 이미 지났는데 status는 아직 READY로 남아 있는 상태.
	// status만 봤다면 통과했을 요청이므로, 시간 조건이 실제로 막는지 확인한다.
	@Test
	void updateCouponThrowsIssueAlreadyStartedWhenIssueStartAtHasPassedButStatusIsStillReady() {
		Event event = scheduledEvent();
		Coupon coupon = readyCouponWithIssueStartAt(event, NOW.minusMinutes(1));
		CouponStock couponStock = mock(CouponStock.class);
		CouponUpdateRequest request = CouponUpdateRequest.builder().name("가을 쿠폰").build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_ALREADY_STARTED, exception.getErrorCode());
		verifyNoInteractions(couponConverter);
	}

	// [#222] validateIssueNotStarted는 "기존" issueStartAt만 본다 — 요청이 실어보낸 새
	// issueStartAt이 과거인 경우는 이 검증을 그대로 통과하므로, 뒤이은 validateIssuePeriod의
	// 과거 시각 검증이 실제로 이 경로를 막는지 확인한다. 기존 issueStartAt은 미래로 둬서
	// validateIssueNotStarted는 통과시키고, 요청값만 과거로 보낸다.
	@Test
	void updateCouponThrowsIssueStartAtInPastWhenRequestedIssueStartAtIsBeforeNow() {
		Event event = alreadyOpenEvent();
		Coupon coupon = Coupon.builder()
				.event(event)
				.name("여름 정률 쿠폰")
				.discountType(DiscountType.RATE)
				.discountValue(20)
				.minOrderAmount(30_000)
				.maxDiscountAmount(10_000)
				.issueStartAt(NOW.plusHours(1))
				.issueEndAt(NOW.plusDays(3))
				.validDays(7)
				.build();
		CouponStock couponStock = mock(CouponStock.class);
		CouponUpdateRequest request = CouponUpdateRequest.builder()
				.issueStartAt(NOW.minusDays(1))
				.build();

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> couponService.updateCoupon(EVENT_ID, COUPON_ID, request)
		);

		assertSame(CouponErrorCode.ISSUE_START_AT_IN_PAST, exception.getErrorCode());
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

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

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

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

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

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

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

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));
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

		when(couponRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(coupon));
		when(couponStockRepository.findByIdForUpdate(COUPON_ID)).thenReturn(Optional.of(couponStock));

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

	// [#222] issueStartAt을 과거로 바꾸는 요청을 이벤트 기간 검증(openAt 이전 거부)이 아니라
	// 과거 시각 검증에서 걸리게 하려면, 이벤트 자체가 이미 시작된 상태여야 한다.
	private Event alreadyOpenEvent() {
		Event event = mock(Event.class);
		when(event.getEventId()).thenReturn(EVENT_ID);
		when(event.getStatus()).thenReturn(EventStatus.SCHEDULED);
		lenient().when(event.getOpenAt()).thenReturn(NOW.minusDays(5));
		lenient().when(event.getCloseAt()).thenReturn(NOW.plusDays(5));
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

	private Coupon readyCouponWithIssueStartAt(Event event, LocalDateTime issueStartAt) {
		return Coupon.builder()
				.event(event)
				.name("여름 정률 쿠폰")
				.discountType(DiscountType.RATE)
				.discountValue(20)
				.minOrderAmount(30_000)
				.maxDiscountAmount(10_000)
				.issueStartAt(issueStartAt)
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
