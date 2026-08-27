package com.mycom.petcoupon.coupon.dto.res;

import java.util.List;

import lombok.Builder;

// 발급 처리량/상태 통계(#156) — 프론트가 그래프+도넛을 한 화면에 같이 그리는 경우가
// 대부분이라, 왕복을 줄이려고 한 응답에 시간대별 추이(timeSeries)와 현재 상태 분포
// (distribution)를 같이 담는다. 둘은 조회 범위가 다르다 — timeSeries는 요청한 기간
// (since 이후)만, distribution은 전체 기간 대상이다(IssueMessageRepository 참고).
@Builder
public record IssueStatisticsResponse(
        List<IssueThroughputBucketResponse> timeSeries,
        List<IssueStatusDistributionResponse> distribution
) {
}
