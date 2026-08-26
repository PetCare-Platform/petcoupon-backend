package com.mycom.petcoupon.coupon.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponUpdateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;

class CouponConverterTest {

	private final CouponConverter couponConverter = new CouponConverter();

	@Test
	void toCouponMapsEveryRequestField() {
		Event event = mock(Event.class);
		CouponCreateRequest request = request();

		Coupon coupon = couponConverter.toCoupon(event, request);

		assertSame(event, coupon.getEvent());
		assertEquals(request.name(), coupon.getName());
		assertSame(request.discountType(), coupon.getDiscountType());
		assertEquals(request.discountValue().intValue(), coupon.getDiscountValue());
		assertEquals(request.minOrderAmount().intValue(), coupon.getMinOrderAmount());
		assertEquals(request.maxDiscountAmount(), coupon.getMaxDiscountAmount());
		assertEquals(request.issueStartAt(), coupon.getIssueStartAt());
		assertEquals(request.issueEndAt(), coupon.getIssueEndAt());
		assertEquals(request.validDays().intValue(), coupon.getValidDays());
		assertSame(CouponStatus.READY, coupon.getStatus());
	}

	@Test
	void toCouponStockSetsInitialQuantityValues() {
		Coupon coupon = mock(Coupon.class);
		CouponCreateRequest request = request();

		CouponStock couponStock = couponConverter.toCouponStock(coupon, request);

		assertSame(coupon, couponStock.getCoupon());
		assertEquals(request.totalQuantity().intValue(), couponStock.getTotalQuantity());
		assertEquals(0, couponStock.getIssuedQuantity());
		assertEquals(request.totalQuantity().intValue(), couponStock.getRemainingQuantity());
	}

	@Test
	void toCreateResponseMapsCouponAndStockFields() {
		Event event = mock(Event.class);
		Coupon coupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		LocalDateTime issueStartAt = LocalDateTime.of(2026, 8, 21, 9, 0);
		LocalDateTime issueEndAt = LocalDateTime.of(2026, 8, 30, 23, 59);

		when(event.getEventId()).thenReturn(1L);
		when(coupon.getCouponId()).thenReturn(10L);
		when(coupon.getEvent()).thenReturn(event);
		when(coupon.getName()).thenReturn("여름 정률 쿠폰");
		when(coupon.getDiscountType()).thenReturn(DiscountType.RATE);
		when(coupon.getDiscountValue()).thenReturn(20);
		when(coupon.getMinOrderAmount()).thenReturn(30_000);
		when(coupon.getMaxDiscountAmount()).thenReturn(10_000);
		when(coupon.getIssueStartAt()).thenReturn(issueStartAt);
		when(coupon.getIssueEndAt()).thenReturn(issueEndAt);
		when(coupon.getValidDays()).thenReturn(7);
		when(coupon.getStatus()).thenReturn(CouponStatus.READY);
		when(couponStock.getTotalQuantity()).thenReturn(100);

		CouponCreateResponse response = couponConverter.toCreateResponse(coupon, couponStock);

		assertEquals(10L, response.couponId());
		assertEquals(1L, response.eventId());
		assertEquals("여름 정률 쿠폰", response.name());
		assertSame(DiscountType.RATE, response.discountType());
		assertEquals(20, response.discountValue());
		assertEquals(30_000, response.minOrderAmount());
		assertEquals(10_000, response.maxDiscountAmount());
		assertEquals(issueStartAt, response.issueStartAt());
		assertEquals(issueEndAt, response.issueEndAt());
		assertEquals(7, response.validDays());
		assertEquals(100, response.totalQuantity());
		assertSame(CouponStatus.READY, response.status());
	}

	@Test
	void toUpdateResponseMapsCouponAndStockFields() {
		Event event = mock(Event.class);
		Coupon coupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		LocalDateTime issueStartAt = LocalDateTime.of(2026, 8, 21, 9, 0);
		LocalDateTime issueEndAt = LocalDateTime.of(2026, 8, 30, 23, 59);

		when(event.getEventId()).thenReturn(1L);
		when(coupon.getCouponId()).thenReturn(10L);
		when(coupon.getEvent()).thenReturn(event);
		when(coupon.getName()).thenReturn("가을 정률 쿠폰");
		when(coupon.getDiscountType()).thenReturn(DiscountType.RATE);
		when(coupon.getDiscountValue()).thenReturn(30);
		when(coupon.getMinOrderAmount()).thenReturn(20_000);
		when(coupon.getMaxDiscountAmount()).thenReturn(15_000);
		when(coupon.getIssueStartAt()).thenReturn(issueStartAt);
		when(coupon.getIssueEndAt()).thenReturn(issueEndAt);
		when(coupon.getValidDays()).thenReturn(10);
		when(coupon.getStatus()).thenReturn(CouponStatus.READY);
		when(couponStock.getTotalQuantity()).thenReturn(200);

		CouponUpdateResponse response = couponConverter.toUpdateResponse(coupon, couponStock);

		assertEquals(10L, response.couponId());
		assertEquals(1L, response.eventId());
		assertEquals("가을 정률 쿠폰", response.name());
		assertSame(DiscountType.RATE, response.discountType());
		assertEquals(30, response.discountValue());
		assertEquals(20_000, response.minOrderAmount());
		assertEquals(15_000, response.maxDiscountAmount());
		assertEquals(issueStartAt, response.issueStartAt());
		assertEquals(issueEndAt, response.issueEndAt());
		assertEquals(10, response.validDays());
		assertEquals(200, response.totalQuantity());
		assertSame(CouponStatus.READY, response.status());
	}

	@Test
	void toUpdateResponseUsesCouponUpdatedAtWhenItIsLaterThanStock() {
		LocalDateTime couponUpdatedAt = LocalDateTime.of(2026, 8, 25, 11, 0);
		LocalDateTime stockUpdatedAt = LocalDateTime.of(2026, 8, 25, 10, 0);
		Coupon coupon = couponWithUpdatedAt(couponUpdatedAt);
		CouponStock couponStock = mock(CouponStock.class);

		when(couponStock.getUpdatedAt()).thenReturn(stockUpdatedAt);

		CouponUpdateResponse response = couponConverter.toUpdateResponse(coupon, couponStock);

		assertEquals(couponUpdatedAt, response.updatedAt());
	}

	// 총수량만 바꾼 요청은 Coupon에 실제 변경이 없어 coupon.updatedAt이 그대로 남는다.
	@Test
	void toUpdateResponseUsesStockUpdatedAtWhenItIsLaterThanCoupon() {
		LocalDateTime couponUpdatedAt = LocalDateTime.of(2026, 8, 25, 10, 0);
		LocalDateTime stockUpdatedAt = LocalDateTime.of(2026, 8, 25, 11, 0);
		Coupon coupon = couponWithUpdatedAt(couponUpdatedAt);
		CouponStock couponStock = mock(CouponStock.class);

		when(couponStock.getUpdatedAt()).thenReturn(stockUpdatedAt);

		CouponUpdateResponse response = couponConverter.toUpdateResponse(coupon, couponStock);

		assertEquals(stockUpdatedAt, response.updatedAt());
	}

	private Coupon couponWithUpdatedAt(LocalDateTime updatedAt) {
		Event event = mock(Event.class);
		Coupon coupon = mock(Coupon.class);

		when(event.getEventId()).thenReturn(1L);
		when(coupon.getEvent()).thenReturn(event);
		when(coupon.getUpdatedAt()).thenReturn(updatedAt);

		return coupon;
	}

	private CouponCreateRequest request() {
		return CouponCreateRequest.builder()
				.name("여름 정률 쿠폰")
				.discountType(DiscountType.RATE)
				.discountValue(20)
				.minOrderAmount(30_000)
				.maxDiscountAmount(10_000)
				.issueStartAt(LocalDateTime.of(2026, 8, 21, 9, 0))
				.issueEndAt(LocalDateTime.of(2026, 8, 30, 23, 59))
				.validDays(7)
				.totalQuantity(100)
				.build();
	}
}
