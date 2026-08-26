package com.mycom.petcoupon.event.service;

import java.util.List;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.req.EventStatusUpdateRequest;
import com.mycom.petcoupon.event.dto.req.EventUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;

public interface EventService {
	EventCreateResponse createEvent(EventCreateRequest request);
    List<EventListResponse> getOpenEvents();
    List<EventListResponse> getAllEvents();
	EventDetailResponse getEventDetail(Long eventId);
	EventStatusResponse getEventStatus(Long eventId);
	EventUpdateResponse updateEvent(Long eventId, EventUpdateRequest request);
	EventUpdateResponse updateEventStatus(Long eventId, EventStatusUpdateRequest request);
}
