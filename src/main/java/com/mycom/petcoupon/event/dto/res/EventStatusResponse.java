package com.mycom.petcoupon.event.dto.res;

import com.mycom.petcoupon.event.entity.enums.EventStatus;

public record EventStatusResponse(
		Long eventId,
		EventStatus status
) {
}
