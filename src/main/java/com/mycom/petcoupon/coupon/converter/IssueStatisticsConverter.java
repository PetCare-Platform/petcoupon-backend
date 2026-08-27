package com.mycom.petcoupon.coupon.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.res.IssueStatusDistributionResponse;
import com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse;
import com.mycom.petcoupon.messaging.repository.IssueStatusCount;
import com.mycom.petcoupon.messaging.repository.IssueThroughputBucket;

@Component
public class IssueStatisticsConverter {

    public IssueThroughputBucketResponse toBucketResponse(IssueThroughputBucket bucket) {
        return IssueThroughputBucketResponse.builder()
                .bucket(bucket.getBucket())
                .issuedCount(bucket.getIssuedCount())
                .failedCount(bucket.getFailedCount())
                .build();
    }

    public IssueStatusDistributionResponse toDistributionResponse(IssueStatusCount count) {
        return IssueStatusDistributionResponse.builder()
                .status(count.getStatus())
                .count(count.getCount())
                .build();
    }
}
