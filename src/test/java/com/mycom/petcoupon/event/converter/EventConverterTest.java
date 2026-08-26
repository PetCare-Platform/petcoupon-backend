package com.mycom.petcoupon.event.converter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
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
