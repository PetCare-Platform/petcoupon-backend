package com.mycom.petcoupon.dashboard.dto.res;

import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;

import lombok.Builder;

// 대시보드 요약 집계(#172) — 발급 건 상태 분포 도넛차트 한 조각.
//
// IssueStatisticsConverter가 만드는 상태 분포(#156)와는 대상이 다르다 — 그건 발급
// 파이프라인 메시지(issue_message: PENDING/SENT/CONSUMED/FAILED/DLQ/ABANDONED) 기준이고,
// 이건 발급이 실제로 확정된 결과(coupon_issue: ISSUED/USED/EXPIRED) 기준이다.
@Builder
public record CouponIssueStatusDistributionResponse(
        IssueStatus status,
        long count
) {
}
