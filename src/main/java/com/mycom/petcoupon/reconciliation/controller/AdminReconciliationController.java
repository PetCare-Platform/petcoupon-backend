package com.mycom.petcoupon.reconciliation.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.reconciliation.batch.service.ReconciliationJobTriggerService;
import com.mycom.petcoupon.reconciliation.converter.ReconciliationConverter;
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
        ReconciliationReport report = reconciliationJobTriggerService.reconcile(couponId);
        ReconciliationTriggerResponse response = reconciliationConverter.toTriggerResponse(report);

        return CustomResponse.onSuccess(response);
    }
}
