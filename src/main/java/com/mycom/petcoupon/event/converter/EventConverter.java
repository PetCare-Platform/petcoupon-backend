package com.mycom.petcoupon.event.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
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

	public EventCreateResponse toCreateResponse(Event event) {
		return EventCreateResponse.builder()
				.eventId(event.getEventId())
				.name(event.getName())
				.description(event.getDescription())
				.openAt(event.getOpenAt())
				.closeAt(event.getCloseAt())
				.status(event.getStatus())
				.build();
	}

	public EventDetailResponse toDetailResponse(Event event) {
		return new EventDetailResponse(
				event.getEventId(),
				event.getName(),
				event.getDescription(),
				event.getOpenAt(),
				event.getCloseAt(),
				event.getStatus()
		);
	}

	public EventStatusResponse toStatusResponse(Long eventId, EventStatus status) {
		return new EventStatusResponse(eventId, status);
	}
}
