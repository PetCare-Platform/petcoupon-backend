package com.mycom.petcoupon.system.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.system.converter.SystemHealthConverter;
import com.mycom.petcoupon.system.dto.res.SystemHealthResponse;
import com.mycom.petcoupon.system.service.SystemHealthService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/system/health")
@RequiredArgsConstructor
public class SystemHealthController {

    private final SystemHealthService systemHealthService;
    private final SystemHealthConverter systemHealthConverter;

    @GetMapping
    public CustomResponse<SystemHealthResponse> getHealth() {
        return CustomResponse.onSuccess(systemHealthConverter.toResponse(systemHealthService.getSnapshot()));
    }
}
