package com.mycom.petcoupon.reconciliation.batch.chunk;

/**
 * invalidTransitionStep 청크 Reader가 한 행씩 읽어오는 원시 데이터.
 * INVALID_STATUS 검증 조건(ReconciliationJobConfig의 invalidTransitionReader 참고)의
 * 페이징 조회 결과.
 */
public record InvalidTransitionRow(
        Long couponIssueId,
        Long userId,
        String fromStatus,
        String toStatus
) {
}
