package com.mycom.petcoupon.event.dto.res;

import java.time.LocalDateTime;

import com.mycom.petcoupon.event.entity.enums.EventStatus;

public record EventCreateResponse(
		Long eventId,
		String name,
		String description,
		LocalDateTime openAt,
		LocalDateTime closeAt,
		EventStatus status
) {
}
