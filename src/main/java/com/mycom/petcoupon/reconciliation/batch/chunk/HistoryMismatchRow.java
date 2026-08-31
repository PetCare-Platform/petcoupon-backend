package com.mycom.petcoupon.reconciliation.batch.chunk;

/**
 * historyMismatchStep 청크 Reader가 한 행씩 읽어오는 원시 데이터.
 * HISTORY_MISMATCH 검증 조건(ReconciliationJobConfig의 historyMismatchReader 참고)의
 * 페이징 조회 결과 — Reader의 SELECT 컬럼과 1:1로 대응한다.
 */
public record HistoryMismatchRow(
        Long couponIssueId,
        Long userId,
        String status,
        String toStatus
) {
}
