package com.mycom.petcoupon.coupon.converter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;

class CouponConverterTest {

	private static final LocalDateTime ISSUE_START_AT = LocalDateTime.of(2026, 8, 20, 10, 0);
	private static final LocalDateTime ISSUE_END_AT = LocalDateTime.of(2026, 8, 31, 23, 59);

	private final CouponConverter couponConverter = new CouponConverter();

	@Test
	void toCouponMapsAllRequestFieldsAndSetsDefaults() {
		Event event = mock(Event.class);
		CouponCreateRequest request = couponCreateRequest();

		Coupon coupon = couponConverter.toCoupon(event, request);

		assertAll(
				() -> assertSame(event, coupon.getEvent()),
				() -> assertEquals(request.name(), coupon.getName()),
				() -> assertSame(request.discountType(), coupon.getDiscountType()),
				() -> assertEquals(request.discountValue().intValue(), coupon.getDiscountValue()),
				() -> assertEquals(request.minOrderAmount().intValue(), coupon.getMinOrderAmount()),
				() -> assertEquals(request.maxDiscountAmount(), coupon.getMaxDiscountAmount()),
				() -> assertEquals(request.issueStartAt(), coupon.getIssueStartAt()),
				() -> assertEquals(request.issueEndAt(), coupon.getIssueEndAt()),
				() -> assertEquals(request.validDays().intValue(), coupon.getValidDays()),
				() -> assertSame(CouponStatus.READY, coupon.getStatus()),
				() -> assertEquals(1, coupon.getLimitPerMember())
		);
	}

	@Test
	void toCouponStockSetsInitialStockFromTotalQuantity() {
		Coupon coupon = mock(Coupon.class);
		CouponCreateRequest request = couponCreateRequest();

		CouponStock couponStock = couponConverter.toCouponStock(coupon, request);

		assertAll(
				() -> assertSame(coupon, couponStock.getCoupon()),
				() -> assertEquals(request.totalQuantity().intValue(), couponStock.getTotalQuantity()),
				() -> assertEquals(0, couponStock.getIssuedQuantity()),
				() -> assertEquals(request.totalQuantity().intValue(), couponStock.getRemainingQuantity())
		);
	}

	@Test
	void toCreateResponseMapsAllCouponAndStockFields() {
		Event event = mock(Event.class);
		Coupon coupon = mock(Coupon.class);
		CouponStock couponStock = mock(CouponStock.class);
		when(event.getEventId()).thenReturn(7L);
		when(coupon.getCouponId()).thenReturn(11L);
		when(coupon.getEvent()).thenReturn(event);
		when(coupon.getName()).thenReturn("반려동물 여름 할인 쿠폰");
		when(coupon.getDiscountType()).thenReturn(DiscountType.RATE);
		when(coupon.getDiscountValue()).thenReturn(20);
		when(coupon.getMinOrderAmount()).thenReturn(30_000);
		when(coupon.getMaxDiscountAmount()).thenReturn(10_000);
		when(coupon.getIssueStartAt()).thenReturn(ISSUE_START_AT);
		when(coupon.getIssueEndAt()).thenReturn(ISSUE_END_AT);
		when(coupon.getValidDays()).thenReturn(14);
		when(coupon.getStatus()).thenReturn(CouponStatus.ACTIVE);
		when(couponStock.getTotalQuantity()).thenReturn(500);

		CouponCreateResponse response = couponConverter.toCreateResponse(coupon, couponStock);

		assertAll(
				() -> assertEquals(11L, response.couponId()),
				() -> assertEquals(7L, response.eventId()),
				() -> assertEquals("반려동물 여름 할인 쿠폰", response.name()),
				() -> assertSame(DiscountType.RATE, response.discountType()),
				() -> assertEquals(20, response.discountValue()),
				() -> assertEquals(30_000, response.minOrderAmount()),
				() -> assertEquals(10_000, response.maxDiscountAmount().intValue()),
				() -> assertEquals(ISSUE_START_AT, response.issueStartAt()),
				() -> assertEquals(ISSUE_END_AT, response.issueEndAt()),
				() -> assertEquals(14, response.validDays()),
				() -> assertEquals(500, response.totalQuantity()),
				() -> assertSame(CouponStatus.ACTIVE, response.status())
		);
	}

	private CouponCreateRequest couponCreateRequest() {
		return new CouponCreateRequest(
				"반려동물 여름 할인 쿠폰",
				DiscountType.RATE,
				20,
				30_000,
				10_000,
				ISSUE_START_AT,
				ISSUE_END_AT,
				14,
				500
		);
	}
}
