package com.mycom.petcoupon.event.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.event.dto.EventCreateRequest;
import com.mycom.petcoupon.event.dto.EventCreateResponse;
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
	public ResponseEntity<CustomResponse<EventCreateResponse>> createEvent(
			@Valid @RequestBody EventCreateRequest request
	) {
		EventCreateResponse response = eventService.createEvent(request);

		return ResponseEntity
				.status(HttpStatus.CREATED)
				.body(CustomResponse.onSuccess(HttpStatus.CREATED, response));
	}
}
