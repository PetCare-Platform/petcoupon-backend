package com.mycom.petcoupon.event.service;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.req.EventPageRequest;
import com.mycom.petcoupon.event.dto.req.EventStatusUpdateRequest;
import com.mycom.petcoupon.event.dto.req.EventUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventPageResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;

public interface EventService {
	EventCreateResponse createEvent(EventCreateRequest request);
	EventPageResponse getOpenEvents(EventPageRequest pageRequest);
	EventPageResponse getAllEvents(EventPageRequest pageRequest);
	EventDetailResponse getEventDetail(Long eventId);
	EventStatusResponse getEventStatus(Long eventId);
	EventUpdateResponse updateEvent(Long eventId, EventUpdateRequest request);
	EventUpdateResponse updateEventStatus(Long eventId, EventStatusUpdateRequest request);
}
