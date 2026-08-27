package com.mycom.petcoupon.reconciliation.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.reconciliation.batch.service.ReconciliationBatchResult;
import com.mycom.petcoupon.reconciliation.batch.service.ReconciliationJobTriggerService;
import com.mycom.petcoupon.reconciliation.converter.ReconciliationConverter;
import com.mycom.petcoupon.reconciliation.dto.res.ReconciliationReportSummaryResponse;
import com.mycom.petcoupon.reconciliation.dto.res.ReconciliationTriggerResponse;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/coupons")
@RequiredArgsConstructor
public class AdminReconciliationController {

    private final ReconciliationJobTriggerService reconciliationJobTriggerService;
    private final ReconciliationConverter reconciliationConverter;

    @PostMapping("/{couponId}/reconcile")
    public CustomResponse<ReconciliationTriggerResponse> reconcile(
            @PathVariable("couponId") Long couponId
    ) {
        ReconciliationBatchResult result = reconciliationJobTriggerService.reconcile(couponId);
        ReconciliationTriggerResponse response = reconciliationConverter.toTriggerResponse(result);

        return CustomResponse.onSuccess(response);
    }

    // 이력 목록(#154) — DLQ 목록 조회(list-size)와 동일하게 limit으로 응답 크기를 제한한다.
    // 배치를 새로 돌리지 않고 이미 쌓인 리포트만 최신순으로 반환한다.
    @GetMapping("/{couponId}/reconciliation-reports")
    public CustomResponse<List<ReconciliationReportSummaryResponse>> listReconciliationReports(
            @PathVariable("couponId") Long couponId,
            @RequestParam(name = "limit", defaultValue = "30") int limit
    ) {
        List<ReconciliationReport> reports = reconciliationJobTriggerService.listHistory(couponId, limit);
        List<ReconciliationReportSummaryResponse> response = reports.stream()
                .map(reconciliationConverter::toSummaryResponse)
                .toList();

        return CustomResponse.onSuccess(response);
    }
}
