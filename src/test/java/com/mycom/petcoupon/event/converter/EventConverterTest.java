package com.mycom.petcoupon.event.converter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCouponResponse;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.dto.res.PublicEventDetailResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.user.entity.AppUser;

class EventConverterTest {

	private final EventConverter eventConverter = new EventConverter();

	@Test
	void toEntityMapsRequestAndSetsScheduledStatus() {
		LocalDateTime openAt = LocalDateTime.of(2026, 8, 20, 9, 0);
		LocalDateTime closeAt = LocalDateTime.of(2026, 8, 31, 23, 59);
		EventCreateRequest request = new EventCreateRequest(
				"반려동물 여름 이벤트",
				"선착순 쿠폰 이벤트",
				openAt,
				closeAt
		);
		AppUser createdBy = mock(AppUser.class);

		Event event = eventConverter.toEntity(request, createdBy);

		assertAll(
				() -> assertSame(createdBy, event.getCreatedBy()),
				() -> assertEquals(request.name(), event.getName()),
				() -> assertEquals(request.description(), event.getDescription()),
				() -> assertEquals(openAt, event.getOpenAt()),
				() -> assertEquals(closeAt, event.getCloseAt()),
				() -> assertSame(EventStatus.SCHEDULED, event.getStatus())
		);
	}

	@Test
	void toCreateResponseMapsAllEventFields() {
		Event event = eventFixture();

		EventCreateResponse response = eventConverter.toCreateResponse(event);

		assertAllEventFields(
				response.eventId(),
				response.name(),
				response.description(),
				response.openAt(),
				response.closeAt(),
				response.status()
		);
	}

	@Test
	void toDetailResponseMapsAllEventFields() {
		Event event = eventFixture();

		EventDetailResponse response = eventConverter.toDetailResponse(event);

		assertAllEventFields(
				response.eventId(),
				response.name(),
				response.description(),
				response.openAt(),
				response.closeAt(),
				response.status()
		);
	}

	@Test
	void toListResponseMapsAllEventFields() {
		Event event = eventFixture();

		EventListResponse response = eventConverter.toListResponse(event);

		assertAllEventFields(
				response.eventId(),
				response.name(),
				response.description(),
				response.openAt(),
				response.closeAt(),
				response.status()
		);
	}

	@Test
	void toUpdateResponseMapsAllEventFields() {
		Event event = eventFixture();

		EventUpdateResponse response = eventConverter.toUpdateResponse(event);

		assertAllEventFields(
				response.eventId(),
				response.name(),
				response.description(),
				response.openAt(),
				response.closeAt(),
				response.status()
		);
	}

	@Test
	void toStatusResponseMapsEventIdAndStatus() {
		EventStatusResponse response = eventConverter.toStatusResponse(1L, EventStatus.OPEN);

		assertAll(
				() -> assertEquals(1L, response.eventId()),
				() -> assertSame(EventStatus.OPEN, response.status())
		);
	}

	@Test
	void toEventCouponResponseMapsAllCouponFields() {
		Coupon coupon = couponFixture();

		EventCouponResponse response = eventConverter.toEventCouponResponse(coupon);

		assertAll(
				() -> assertEquals(10L, response.couponId()),
				() -> assertEquals("10% 할인 쿠폰", response.name()),
				() -> assertSame(DiscountType.RATE, response.discountType()),
				() -> assertEquals(10, response.discountValue()),
				() -> assertEquals(20000, response.minOrderAmount()),
				() -> assertEquals(5000, response.maxDiscountAmount()),
				() -> assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), response.issueStartAt()),
				() -> assertEquals(LocalDateTime.of(2026, 8, 31, 23, 59, 59), response.issueEndAt()),
				() -> assertEquals(30, response.validDays()),
				() -> assertSame(CouponStatus.ACTIVE, response.status())
		);
	}

	@Test
	void toPublicEventDetailResponseCombinesEventAndCoupons() {
		Event event = eventFixture();

		PublicEventDetailResponse response = eventConverter.toPublicEventDetailResponse(
				event, List.of(couponFixture())
		);

		assertAll(
				() -> assertEquals(1L, response.eventId()),
				() -> assertEquals("반려동물 여름 이벤트", response.name()),
				() -> assertEquals("선착순 쿠폰 이벤트", response.description()),
				() -> assertEquals(LocalDateTime.of(2026, 8, 20, 9, 0), response.openAt()),
				() -> assertEquals(LocalDateTime.of(2026, 8, 31, 23, 59), response.closeAt()),
				() -> assertSame(EventStatus.SCHEDULED, response.status()),
				() -> assertEquals(1, response.coupons().size()),
				() -> assertEquals(10L, response.coupons().get(0).couponId())
		);
	}

	@Test
	void toPublicEventDetailResponseReturnsEmptyCouponsWhenNoneGiven() {
		Event event = eventFixture();

		PublicEventDetailResponse response = eventConverter.toPublicEventDetailResponse(event, List.of());

		assertTrue(response.coupons().isEmpty());
	}

	private Coupon couponFixture() {
		Coupon coupon = mock(Coupon.class);
		when(coupon.getCouponId()).thenReturn(10L);
		when(coupon.getName()).thenReturn("10% 할인 쿠폰");
		when(coupon.getDiscountType()).thenReturn(DiscountType.RATE);
		when(coupon.getDiscountValue()).thenReturn(10);
		when(coupon.getMinOrderAmount()).thenReturn(20000);
		when(coupon.getMaxDiscountAmount()).thenReturn(5000);
		when(coupon.getIssueStartAt()).thenReturn(LocalDateTime.of(2026, 8, 1, 0, 0));
		when(coupon.getIssueEndAt()).thenReturn(LocalDateTime.of(2026, 8, 31, 23, 59, 59));
		when(coupon.getValidDays()).thenReturn(30);
		when(coupon.getStatus()).thenReturn(CouponStatus.ACTIVE);
		return coupon;
	}

	private Event eventFixture() {
		Event event = mock(Event.class);
		when(event.getEventId()).thenReturn(1L);
		when(event.getName()).thenReturn("반려동물 여름 이벤트");
		when(event.getDescription()).thenReturn("선착순 쿠폰 이벤트");
		when(event.getOpenAt()).thenReturn(LocalDateTime.of(2026, 8, 20, 9, 0));
		when(event.getCloseAt()).thenReturn(LocalDateTime.of(2026, 8, 31, 23, 59));
		when(event.getStatus()).thenReturn(EventStatus.SCHEDULED);
		return event;
	}

	private void assertAllEventFields(
			Long eventId,
			String name,
			String description,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			EventStatus status
	) {
		assertAll(
				() -> assertEquals(1L, eventId),
				() -> assertEquals("반려동물 여름 이벤트", name),
				() -> assertEquals("선착순 쿠폰 이벤트", description),
				() -> assertEquals(LocalDateTime.of(2026, 8, 20, 9, 0), openAt),
				() -> assertEquals(LocalDateTime.of(2026, 8, 31, 23, 59), closeAt),
				() -> assertSame(EventStatus.SCHEDULED, status)
		);
	}
}
