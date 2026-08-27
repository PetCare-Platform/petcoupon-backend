package com.mycom.petcoupon.coupon.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.res.IssueStatisticsResponse;
import com.mycom.petcoupon.coupon.service.IssueStatisticsService;
import com.mycom.petcoupon.global.common.CustomResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/coupon-issue/statistics")
@RequiredArgsConstructor
public class IssueStatisticsController {

    private final IssueStatisticsService issueStatisticsService;

    @GetMapping
    public CustomResponse<IssueStatisticsResponse> getStatistics() {
        return CustomResponse.onSuccess(issueStatisticsService.getStatistics());
    }
}
