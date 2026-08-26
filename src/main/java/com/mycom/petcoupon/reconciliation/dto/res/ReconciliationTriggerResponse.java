package com.mycom.petcoupon.reconciliation.dto.res;

import java.time.LocalDateTime;

import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;

import lombok.Builder;

// 정합성 문제가 있는지는 반드시 result로 판단한다. errorCount는 발급 건(coupon_issue row)
// 단위로 문제가 있는 건수만 세는 보조 지표라, STOCK_MISMATCH/SEQUENCE_GAP처럼 특정 발급 건이
// 아니라 쿠폰 전체를 가리키는 문제만 있을 때는 result=MISMATCHED인데도 errorCount=0일 수 있다.
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
