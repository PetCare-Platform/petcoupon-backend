package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

// 발급 처리량/상태 통계(#156) — 시간대별 처리량 그래프 한 점.
@Builder
public record IssueThroughputBucketResponse(
        String bucket,
        long issuedCount,
        long failedCount
) {
}
