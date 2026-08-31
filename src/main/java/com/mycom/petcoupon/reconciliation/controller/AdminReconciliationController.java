package com.mycom.petcoupon.reconciliation.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
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

    // 이력 목록(#154)의 limit 상한(#155 리뷰 반영) — DLQ 목록의 list-size 기본값(100)과 동일한
    // 규모로 맞췄다.
    private static final int MAX_RECONCILIATION_REPORTS_LIMIT = 100;

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
    //
    // limit은 1~100으로 직접 검증한다(PR #155 리뷰 반영). @Validated + @Min/@Max(어노테이션
    // 기반 메서드 파라미터 검증)를 먼저 시도했지만, standaloneSetup MockMvc로 실측해보니
    // 실제로 걸러지지 않았다(AOP 프록시 없이는 동작 안 함) — 그래서 여기서 직접 체크한다.
    // PageRequest.of(0, limit)이 0 이하를 받으면 IllegalArgumentException을 던지는데, 검증
    // 없이 그대로 두면 GlobalExceptionHandler의 catch-all(Exception.class)로 떨어져 의미
    // 없는 500이 나간다.
    @GetMapping("/{couponId}/reconciliation-reports")
    public CustomResponse<List<ReconciliationReportSummaryResponse>> listReconciliationReports(
            @PathVariable("couponId") Long couponId,
            @RequestParam(name = "limit", defaultValue = "30") int limit
    ) {
        if (limit < 1 || limit > MAX_RECONCILIATION_REPORTS_LIMIT) {
            throw new GeneralException(CommonErrorCode.NOT_VALID_ERROR);
        }

        List<ReconciliationReport> reports = reconciliationJobTriggerService.listHistory(couponId, limit);
        List<ReconciliationReportSummaryResponse> response = reports.stream()
                .map(reconciliationConverter::toSummaryResponse)
                .toList();

        return CustomResponse.onSuccess(response);
    }
}
