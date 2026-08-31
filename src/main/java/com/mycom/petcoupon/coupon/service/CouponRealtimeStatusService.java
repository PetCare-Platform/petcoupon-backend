package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueTimeSeriesResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponPipelineDrainStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;

public interface CouponRealtimeStatusService {

    CouponRealtimeStatusResponse getRealtimeStatus(Long couponId);

    CouponPipelineDrainStatusResponse getPipelineDrainStatus(Long couponId);

    CouponIssueTimeSeriesResponse getIssueTimeSeries(Long couponId, int windowSeconds, int bucketSeconds);
}
