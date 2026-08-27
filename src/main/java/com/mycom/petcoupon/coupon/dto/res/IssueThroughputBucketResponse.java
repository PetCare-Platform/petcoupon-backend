package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

// 발급 처리량/상태 통계(#156) — 시간대별 처리량 그래프 한 점.
//
// bucket은 "요청이 들어온 시간대"(생성 시각) 기준이지 "처리가 끝난 시간대"가 아니다 —
// issuedCount/failedCount/inProgressCount는 그 시간대에 들어온 요청들의 조회 시점 현재
// 상태를 센 것이라, 나중에 재시도가 성공하면 같은 과거 bucket을 다시 조회했을 때 값이
// 바뀔 수 있다(확정된 이력이 아니라 스냅샷). failedCount는 최종 실패(DLQ·ABANDONED)만
// 포함하고, 재시도 대기중인 FAILED는 inProgressCount에 들어간다(PENDING·SENT와 함께) —
// 그래서 issuedCount+failedCount+inProgressCount가 항상 그 버킷의 총 접수량과 같다.
// IssueMessageRepository.findThroughputByHour() 참고.
//
// 24개 버킷 전부가 DB 조회 결과인 건 아니다 — GROUP BY 쿼리 특성상 요청이 0건인
// 시간대는 결과에서 통째로 빠지는데, IssueStatisticsService가 그 빈 슬롯을 이
// 레코드(issuedCount=0, failedCount=0, inProgressCount=0)로 직접 채워 넣는다
// (zero-filling, IssueStatisticsService.buildTimeSeries() 참고). 프론트가 24개
// 연속 배열을 기대하고 그래프를 그릴 수 있게 하기 위함이다.
@Builder
public record IssueThroughputBucketResponse(
        String bucket,
        long issuedCount,
        long failedCount,
        long inProgressCount
) {
}
