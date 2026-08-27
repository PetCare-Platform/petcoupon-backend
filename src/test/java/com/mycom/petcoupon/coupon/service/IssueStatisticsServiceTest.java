package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
    private ArgumentCaptor<LocalDateTime> fromCaptor;

    @Captor
    private ArgumentCaptor<LocalDateTime> toCaptor;

    // PR 리뷰 반영 — now()를 그대로 24시간 빼면 정각에 안 맞아서 맨 앞 버킷이 부분치만
    // 담기는 문제가 있었다. from/to가 항상 정각(분·초·나노초 0)인지, 정확히 24시간
    // 간격인지, to가 "현재 시(hour) + 1시간"(진행 중인 이번 시간까지 포함)인지 확인한다.
    @Test
    void getStatistics는_정각으로_정렬된_24시간_범위를_시간대별_조회_기준으로_넘긴다() {
        when(issueMessageRepository.findThroughputByHour(any(), any())).thenReturn(List.of());
        when(issueMessageRepository.countGroupedByStatus()).thenReturn(List.of());

        // 서비스 호출 전후로 "현재 정각+1시간" 후보를 각각 계산해둔다 — 호출 직후 단
        // 한 번만 now()를 재서 비교하면, 호출 전후로 정각을 넘어가는 극히 드문 순간에
        // 테스트가 흔들릴 수 있다(예: 서비스 안에서는 13:59:59에 계산했는데 이 검증
        // 시점엔 14:00:00이 된 경우).
        LocalDateTime expectedToBefore = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        issueStatisticsService.getStatistics();
        LocalDateTime expectedToAfter = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);

        verify(issueMessageRepository).findThroughputByHour(fromCaptor.capture(), toCaptor.capture());
        LocalDateTime from = fromCaptor.getValue();
        LocalDateTime to = toCaptor.getValue();

        assertThat(from).isEqualTo(from.truncatedTo(ChronoUnit.HOURS));
        assertThat(to).isEqualTo(to.truncatedTo(ChronoUnit.HOURS));
        assertThat(java.time.Duration.between(from, to).toHours()).isEqualTo(24L);
        // to는 "현재 정각 + 1시간" — 지금 이 순간이 속한 시간대까지 포함해야 한다.
        assertThat(to).isIn(expectedToBefore, expectedToAfter);
    }

    // countGroupedByStatus()는 findThroughputByHour와 달리 시간 범위를 안 받는다 —
    // IssueMessageRepository의 설계 의도(전체 잔량 집계) 그대로 호출되는지 확인한다.
    @Test
    void getStatistics는_상태분포는_시간_제한_없이_전체를_조회한다() {
        when(issueMessageRepository.findThroughputByHour(any(), any())).thenReturn(List.of());
        when(issueMessageRepository.countGroupedByStatus()).thenReturn(List.of());

        issueStatisticsService.getStatistics();

        verify(issueMessageRepository).countGroupedByStatus();
    }

    @Test
    void getStatistics는_두_쿼리_결과를_각각_변환해서_timeSeries와_distribution에_담는다() {
        IssueThroughputBucket bucket = org.mockito.Mockito.mock(IssueThroughputBucket.class);
        IssueStatusCount count = org.mockito.Mockito.mock(IssueStatusCount.class);

        when(issueMessageRepository.findThroughputByHour(any(), any())).thenReturn(List.of(bucket));
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
