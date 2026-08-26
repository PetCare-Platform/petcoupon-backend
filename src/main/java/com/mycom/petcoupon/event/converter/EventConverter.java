package com.mycom.petcoupon.event.converter;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
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
		return toEventResponse(event, EventCreateResponse::new);
	}

	public EventDetailResponse toDetailResponse(Event event) {
		return toEventResponse(event, EventDetailResponse::new);
	}

	public EventListResponse toListResponse(Event event) {
		return toEventResponse(event, EventListResponse::new);
	}

	public EventUpdateResponse toUpdateResponse(Event event) {
		return toEventResponse(event, EventUpdateResponse::new);
	}

	public EventStatusResponse toStatusResponse(Long eventId, EventStatus status) {
		return EventStatusResponse.builder()
				.eventId(eventId)
				.status(status)
				.build();
	}

	private <T> T toEventResponse(Event event, EventResponseFactory<T> factory) {
		return factory.create(
				event.getEventId(),
				event.getName(),
				event.getDescription(),
				event.getOpenAt(),
				event.getCloseAt(),
				event.getStatus()
		);
	}

	@FunctionalInterface
	private interface EventResponseFactory<T> {
		T create(
				Long eventId,
				String name,
				String description,
				LocalDateTime openAt,
				LocalDateTime closeAt,
				EventStatus status
		);
	}
}
