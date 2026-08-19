package com.mycom.petcoupon.event.service;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;

public interface EventService {
	EventCreateResponse createEvent(EventCreateRequest request);
}
