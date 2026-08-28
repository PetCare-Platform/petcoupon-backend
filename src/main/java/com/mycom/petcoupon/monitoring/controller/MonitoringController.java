package com.mycom.petcoupon.monitoring.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.monitoring.dto.req.MonitoringSettingsUpdateRequest;
import com.mycom.petcoupon.monitoring.dto.res.MonitoringSettingsResponse;
import com.mycom.petcoupon.monitoring.service.MonitoringSseService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringSseService monitoringSseService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return monitoringSseService.connect();
    }

    @GetMapping("/settings")
    public CustomResponse<MonitoringSettingsResponse> getSettings() {
        return CustomResponse.onSuccess(new MonitoringSettingsResponse(monitoringSseService.isStreamEnabled()));
    }

    @PatchMapping("/settings")
    public CustomResponse<MonitoringSettingsResponse> updateSettings(
            @Valid @RequestBody MonitoringSettingsUpdateRequest request
    ) {
        monitoringSseService.setStreamEnabled(request.streamEnabled());
        return CustomResponse.onSuccess(new MonitoringSettingsResponse(monitoringSseService.isStreamEnabled()));
    }
}
