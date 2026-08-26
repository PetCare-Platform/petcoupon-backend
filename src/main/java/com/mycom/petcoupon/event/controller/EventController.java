package com.mycom.petcoupon.event.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.event.dto.req.EventPageRequest;
import com.mycom.petcoupon.event.dto.res.EventPageResponse;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

	private final EventService eventService;

	@GetMapping
	public CustomResponse<EventPageResponse> getOpenEvents(
			@RequestParam(name = "page", defaultValue = EventPageRequest.DEFAULT_PAGE) String page,
			@RequestParam(name = "size", defaultValue = EventPageRequest.DEFAULT_SIZE) String size
	) {
		EventPageResponse response = eventService.getOpenEvents(EventPageRequest.from(page, size));

		return CustomResponse.onSuccess(response);
	}
}
