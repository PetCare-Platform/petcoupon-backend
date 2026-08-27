package com.mycom.petcoupon.coupon.dto.res;

import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;

import lombok.Builder;

// 발급 처리량/상태 통계(#156) — 상태 분포 도넛차트 한 조각.
@Builder
public record IssueStatusDistributionResponse(
        IssueMessageStatus status,
        long count
) {
}
