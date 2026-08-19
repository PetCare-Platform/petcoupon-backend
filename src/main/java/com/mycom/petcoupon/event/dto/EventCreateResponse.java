package com.mycom.petcoupon.event.dto;

import java.time.LocalDateTime;

import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;

public record EventCreateResponse(
		Long eventId,
		String name,
		String description,
		LocalDateTime openAt,
		LocalDateTime closeAt,
		EventStatus status
) {
	public static EventCreateResponse from(Event event) {
		return new EventCreateResponse(
				event.getEventId(),
				event.getName(),
				event.getDescription(),
				event.getOpenAt(),
				event.getCloseAt(),
				event.getStatus()
		);
	}
}
