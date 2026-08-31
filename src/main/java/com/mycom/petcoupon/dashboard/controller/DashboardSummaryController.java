package com.mycom.petcoupon.dashboard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.dashboard.dto.res.DashboardSummaryResponse;
import com.mycom.petcoupon.dashboard.service.DashboardSummaryService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/dashboard/summary")
@RequiredArgsConstructor
public class DashboardSummaryController {

    private final DashboardSummaryService dashboardSummaryService;

    @GetMapping
    public CustomResponse<DashboardSummaryResponse> getSummary() {
        return CustomResponse.onSuccess(dashboardSummaryService.getSummary());
    }
}
