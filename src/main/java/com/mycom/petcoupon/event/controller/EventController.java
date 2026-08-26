package com.mycom.petcoupon.event.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.service.EventService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public CustomResponse<List<EventListResponse>> getOpenEvents() {
        List<EventListResponse> response = eventService.getOpenEvents();

        return CustomResponse.onSuccess(response);
    }
}
