package com.mycom.petcoupon.event.service;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.req.EventDescriptionUpdateRequest;
import com.mycom.petcoupon.event.dto.req.EventNameUpdateRequest;
import com.mycom.petcoupon.event.dto.req.EventPeriodUpdateRequest;
import com.mycom.petcoupon.event.dto.req.EventStatusUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;

public interface EventService {
	EventCreateResponse createEvent(EventCreateRequest request);
	EventDetailResponse getEventDetail(Long eventId);
	EventStatusResponse getEventStatus(Long eventId);
	EventUpdateResponse updateEventName(Long eventId, EventNameUpdateRequest request);
	EventUpdateResponse updateEventPeriod(Long eventId, EventPeriodUpdateRequest request);
	EventUpdateResponse updateEventDescription(Long eventId, EventDescriptionUpdateRequest request);
	EventUpdateResponse updateEventStatus(Long eventId, EventStatusUpdateRequest request);
}
