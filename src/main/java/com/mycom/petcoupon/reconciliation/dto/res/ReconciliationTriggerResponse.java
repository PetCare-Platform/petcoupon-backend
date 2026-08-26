package com.mycom.petcoupon.reconciliation.dto.res;

import java.time.LocalDateTime;

import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;

import lombok.Builder;

@Builder
public record ReconciliationTriggerResponse(
        Long reportId,
        Long couponId,
        LocalDateTime asOfAt,
        ReconciliationResult result,
        long totalCount,
        long successCount,
        long errorCount
) {
}
