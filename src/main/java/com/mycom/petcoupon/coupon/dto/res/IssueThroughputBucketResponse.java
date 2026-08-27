package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

// 발급 처리량/상태 통계(#156) — 시간대별 처리량 그래프 한 점.
//
// bucket은 "요청이 들어온 시간대"(생성 시각) 기준이지 "처리가 끝난 시간대"가 아니다 —
// issuedCount/failedCount는 그 시간대에 들어온 요청들의 조회 시점 현재 상태를 센 것이라,
// 나중에 재시도가 성공하면 같은 과거 bucket을 다시 조회했을 때 값이 바뀔 수 있다(확정된
// 이력이 아니라 스냅샷). failedCount는 최종 실패(DLQ·ABANDONED)만 포함하고, 재시도 대기중인
// FAILED는 포함하지 않는다 — IssueMessageRepository.findThroughputByHour() 참고.
@Builder
public record IssueThroughputBucketResponse(
        String bucket,
        long issuedCount,
        long failedCount
) {
}
