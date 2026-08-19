package com.mycom.petcoupon.event.service;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;

public interface EventService {
	EventCreateResponse createEvent(EventCreateRequest request);
	EventDetailResponse getEventDetail(Long eventId);
	EventStatusResponse getEventStatus(Long eventId);
}
