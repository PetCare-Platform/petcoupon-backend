package com.mycom.petcoupon.event.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.event.entity.enums.ActorType;
import com.mycom.petcoupon.event.entity.enums.EventHistoryStatus;

class EventStatusHistoryTest {

	@Test
	void builderSetsAllFields() {
		Event event = mock(Event.class);

		EventStatusHistory history = EventStatusHistory.builder()
				.event(event)
				.fromStatus(EventHistoryStatus.NONE)
				.toStatus(EventHistoryStatus.SCHEDULED)
				.actorType(ActorType.ADMIN)
				.actorId(7L)
				.reason("이벤트 생성")
				.build();

		assertAll(
				() -> assertSame(event, history.getEvent()),
				() -> assertSame(EventHistoryStatus.NONE, history.getFromStatus()),
				() -> assertSame(EventHistoryStatus.SCHEDULED, history.getToStatus()),
				() -> assertSame(ActorType.ADMIN, history.getActorType()),
				() -> assertEquals(7L, history.getActorId()),
				() -> assertEquals("이벤트 생성", history.getReason()),
				() -> assertNull(history.getEventHistoryId()),
				() -> assertNull(history.getCreatedAt())
		);
	}

	@Test
	void builderAllowsNullReason() {
		EventStatusHistory history = EventStatusHistory.builder()
				.event(mock(Event.class))
				.fromStatus(EventHistoryStatus.SCHEDULED)
				.toStatus(EventHistoryStatus.OPEN)
				.actorType(ActorType.SCHEDULER)
				.actorId(null)
				.reason(null)
				.build();

		assertAll(
				() -> assertNull(history.getActorId()),
				() -> assertNull(history.getReason())
		);
	}
}
