package com.mycom.petcoupon.event.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.user.entity.AppUser;

class EventTest {

	@Test
	void builderSetsAllFieldsAndDefaultsStatusToScheduled() {
		AppUser createdBy = mock(AppUser.class);
		LocalDateTime openAt = LocalDateTime.of(2026, 8, 20, 9, 0);
		LocalDateTime closeAt = LocalDateTime.of(2026, 8, 31, 23, 59);

		Event event = Event.builder()
				.createdBy(createdBy)
				.name("반려동물 여름 이벤트")
				.description("선착순 쿠폰 이벤트")
				.openAt(openAt)
				.closeAt(closeAt)
				.build();

		assertAll(
				() -> assertSame(createdBy, event.getCreatedBy()),
				() -> assertEquals("반려동물 여름 이벤트", event.getName()),
				() -> assertEquals("선착순 쿠폰 이벤트", event.getDescription()),
				() -> assertEquals(openAt, event.getOpenAt()),
				() -> assertEquals(closeAt, event.getCloseAt()),
				() -> assertSame(EventStatus.SCHEDULED, event.getStatus()),
				() -> assertNull(event.getEventId())
		);
	}

	@Test
	void builderAllowsNullDescription() {
		Event event = Event.builder()
				.createdBy(mock(AppUser.class))
				.name("반려동물 여름 이벤트")
				.description(null)
				.openAt(LocalDateTime.of(2026, 8, 20, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.build();

		assertNull(event.getDescription());
	}
}
