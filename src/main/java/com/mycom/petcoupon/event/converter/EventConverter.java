package com.mycom.petcoupon.event.converter;

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
		return EventDetailResponse.builder()
				.eventId(event.getEventId())
				.name(event.getName())
				.description(event.getDescription())
				.openAt(event.getOpenAt())
				.closeAt(event.getCloseAt())
				.status(event.getStatus())
				.build();
	}

    public EventListResponse toListResponse(Event event) {
        return EventListResponse.builder()
                .eventId(event.getEventId())
                .name(event.getName())
                .description(event.getDescription())
                .openAt(event.getOpenAt())
                .closeAt(event.getCloseAt())
                .status(event.getStatus())
                .build();
    }

	public EventUpdateResponse toUpdateResponse(Event event) {
		return EventUpdateResponse.builder()
				.eventId(event.getEventId())
				.name(event.getName())
				.description(event.getDescription())
				.openAt(event.getOpenAt())
				.closeAt(event.getCloseAt())
				.status(event.getStatus())
				.build();
	}

	public EventStatusResponse toStatusResponse(Long eventId, EventStatus status) {
		return EventStatusResponse.builder()
				.eventId(eventId)
				.status(status)
				.build();
	}
}
