package com.mycom.petcoupon.event.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.req.EventStatusUpdateRequest;
import com.mycom.petcoupon.event.dto.req.EventUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.CustomResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/events")
@RequiredArgsConstructor
public class AdminEventController {
	private final EventService eventService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CustomResponse<EventCreateResponse> createEvent(
			@Valid @RequestBody EventCreateRequest request
	) {
		EventCreateResponse response = eventService.createEvent(request);

		return CustomResponse.onSuccess(HttpStatus.CREATED, response);
	}

	@GetMapping("/{eventId}")
	public CustomResponse<EventDetailResponse> getEventDetail(@PathVariable("eventId") Long eventId) {
		EventDetailResponse response = eventService.getEventDetail(eventId);

		return CustomResponse.onSuccess(response);
	}

	@GetMapping("/{eventId}/status")
	public CustomResponse<EventStatusResponse> getEventStatus(@PathVariable("eventId") Long eventId) {
		EventStatusResponse response = eventService.getEventStatus(eventId);

		return CustomResponse.onSuccess(response);
	}

	@PatchMapping("/{eventId}")
	public CustomResponse<EventUpdateResponse> updateEvent(
			@PathVariable("eventId") Long eventId,
			@Valid @RequestBody EventUpdateRequest request
	) {
		EventUpdateResponse response = eventService.updateEvent(eventId, request);

		return CustomResponse.onSuccess(response);
	}

	@PatchMapping("/{eventId}/status")
	public CustomResponse<EventUpdateResponse> updateEventStatus(
			@PathVariable("eventId") Long eventId,
			@Valid @RequestBody EventStatusUpdateRequest request
	) {
		EventUpdateResponse response = eventService.updateEventStatus(eventId, request);

		return CustomResponse.onSuccess(response);
	}
}
