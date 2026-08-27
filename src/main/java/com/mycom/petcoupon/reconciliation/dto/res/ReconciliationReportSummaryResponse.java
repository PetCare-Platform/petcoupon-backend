package com.mycom.petcoupon.reconciliation.dto.res;

import java.time.LocalDateTime;

import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;

import lombok.Builder;

// 이력 목록(#154)용 — ReconciliationTriggerResponse와 달리 verificationDetails를 담지 않는다.
// 목록 한 건마다 상세까지 끌고 오면 N건 * MAX_DETAILS_IN_RESPONSE만큼 무거워지는데, 이력 화면은
// 그래프/카드로 추이만 보여주면 되고 상세는 필요할 때 리포트 하나만 따로 조회하면 된다.
@Builder
public record ReconciliationReportSummaryResponse(
        Long reportId,
        Long couponId,
        LocalDateTime asOfAt,
        ReconciliationResult result,
        long totalCount,
        long successCount,
        long errorCount
) {
}
