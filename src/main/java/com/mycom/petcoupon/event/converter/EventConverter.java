package com.mycom.petcoupon.event.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.user.entity.AppUser;

@Component
public class EventConverter {

	public Event toEntity(EventCreateRequest request, AppUser createdBy) {
		return Event.builder()
				.createdBy(createdBy)
				.name(request.name())
				.description(request.description())
				.openAt(request.openAt())
				.closeAt(request.closeAt())
				.build();
	}

	public EventCreateResponse toResponse(Event event) {
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
