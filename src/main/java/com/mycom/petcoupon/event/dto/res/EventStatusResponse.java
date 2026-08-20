package com.mycom.petcoupon.event.dto.res;

import com.mycom.petcoupon.event.entity.enums.EventStatus;

import lombok.Builder;

@Builder
public record EventStatusResponse(
		Long eventId,
		EventStatus status
) {
}
