package com.mycom.petcoupon.coupon.dto.res;

import java.util.List;

import lombok.Builder;

/**
 * 쿠폰별 초 단위 발급 시계열 응답 DTO (#198).
 *
 * <p>특정 쿠폰의 최근 조회 윈도우({@code windowSeconds}) 동안
 * {@code bucketSeconds} 간격으로 집계된 발급 처리량 추이({@code timeSeries})를 반환한다.
 */
@Builder
public record CouponIssueTimeSeriesResponse(
        Long couponId,
        int windowSeconds,
        int bucketSeconds,
        List<IssueThroughputBucketResponse> timeSeries
) {
}
