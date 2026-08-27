package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.converter.IssueStatisticsConverter;
import com.mycom.petcoupon.coupon.dto.res.IssueStatisticsResponse;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.messaging.repository.IssueStatusCount;
import com.mycom.petcoupon.messaging.repository.IssueThroughputBucket;

@ExtendWith(MockitoExtension.class)
class IssueStatisticsServiceTest {

    @Mock
    private IssueMessageRepository issueMessageRepository;

    @Mock
    private IssueStatisticsConverter issueStatisticsConverter;

    @InjectMocks
    private IssueStatisticsService issueStatisticsService;

    @Captor
    private ArgumentCaptor<LocalDateTime> sinceCaptor;

    @Test
    void getStatistics는_최근_24시간을_시간대별_조회_기준으로_넘긴다() {
        when(issueMessageRepository.findThroughputByHour(any())).thenReturn(List.of());
        when(issueMessageRepository.countGroupedByStatus()).thenReturn(List.of());

        LocalDateTime beforeCall = LocalDateTime.now().minusHours(24);
        issueStatisticsService.getStatistics();
        LocalDateTime afterCall = LocalDateTime.now().minusHours(24);

        verify(issueMessageRepository).findThroughputByHour(sinceCaptor.capture());
        LocalDateTime since = sinceCaptor.getValue();

        // 서비스 호출 전후로 "지금-24시간"을 각각 재서 그 사이에 들어오는지 확인한다 —
        // 정확히 now().minusHours(24)와 비교하면 테스트 실행 그 순간의 시각과 어긋나 흔들린다.
        assertThat(since).isBetween(beforeCall.minusSeconds(1), afterCall.plusSeconds(1));
    }

    // countGroupedByStatus()는 findThroughputByHour와 달리 시간 범위를 안 받는다 —
    // IssueMessageRepository의 설계 의도(전체 잔량 집계) 그대로 호출되는지 확인한다.
    @Test
    void getStatistics는_상태분포는_시간_제한_없이_전체를_조회한다() {
        when(issueMessageRepository.findThroughputByHour(any())).thenReturn(List.of());
        when(issueMessageRepository.countGroupedByStatus()).thenReturn(List.of());

        issueStatisticsService.getStatistics();

        verify(issueMessageRepository).countGroupedByStatus();
    }

    @Test
    void getStatistics는_두_쿼리_결과를_각각_변환해서_timeSeries와_distribution에_담는다() {
        IssueThroughputBucket bucket = org.mockito.Mockito.mock(IssueThroughputBucket.class);
        IssueStatusCount count = org.mockito.Mockito.mock(IssueStatusCount.class);

        when(issueMessageRepository.findThroughputByHour(any())).thenReturn(List.of(bucket));
        when(issueMessageRepository.countGroupedByStatus()).thenReturn(List.of(count));

        var bucketResponse = com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse.builder()
                .bucket("2026-08-27 10:00:00").issuedCount(1).failedCount(0).build();
        var distributionResponse = com.mycom.petcoupon.coupon.dto.res.IssueStatusDistributionResponse.builder()
                .status(IssueMessageStatus.CONSUMED).count(1).build();

        when(issueStatisticsConverter.toBucketResponse(bucket)).thenReturn(bucketResponse);
        when(issueStatisticsConverter.toDistributionResponse(count)).thenReturn(distributionResponse);

        IssueStatisticsResponse response = issueStatisticsService.getStatistics();

        assertThat(response.timeSeries()).containsExactly(bucketResponse);
        assertThat(response.distribution()).containsExactly(distributionResponse);
    }
}
