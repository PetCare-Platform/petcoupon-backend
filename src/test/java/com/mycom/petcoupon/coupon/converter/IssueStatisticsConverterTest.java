package com.mycom.petcoupon.coupon.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.dto.res.IssueStatusDistributionResponse;
import com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueStatusCount;
import com.mycom.petcoupon.messaging.repository.IssueThroughputBucket;

class IssueStatisticsConverterTest {

    private final IssueStatisticsConverter converter = new IssueStatisticsConverter();

    @Test
    void toBucketResponse는_프로젝션_필드를_그대로_매핑한다() {
        IssueThroughputBucket bucket = mock(IssueThroughputBucket.class);
        when(bucket.getBucket()).thenReturn("2026-08-27 10:00:00");
        when(bucket.getIssuedCount()).thenReturn(12L);
        when(bucket.getFailedCount()).thenReturn(3L);

        IssueThroughputBucketResponse response = converter.toBucketResponse(bucket);

        assertThat(response.bucket()).isEqualTo("2026-08-27 10:00:00");
        assertThat(response.issuedCount()).isEqualTo(12L);
        assertThat(response.failedCount()).isEqualTo(3L);
    }

    @Test
    void toDistributionResponse는_프로젝션_필드를_그대로_매핑한다() {
        IssueStatusCount count = mock(IssueStatusCount.class);
        when(count.getStatus()).thenReturn(IssueMessageStatus.DLQ);
        when(count.getCount()).thenReturn(5L);

        IssueStatusDistributionResponse response = converter.toDistributionResponse(count);

        assertThat(response.status()).isEqualTo(IssueMessageStatus.DLQ);
        assertThat(response.count()).isEqualTo(5L);
    }
}
