package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.converter.IssueStatisticsConverter;
import com.mycom.petcoupon.coupon.dto.res.IssueStatisticsResponse;
import com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.messaging.repository.IssueStatusCount;
import com.mycom.petcoupon.messaging.repository.IssueThroughputBucket;

@ExtendWith(MockitoExtension.class)
class IssueStatisticsServiceTest {

    // findThroughputByHour의 네이티브 쿼리가 만드는 bucket 포맷(DATE_FORMAT(created_at,
    // '%Y-%m-%d %H:00:00'))과 동일해야 zero-filling 검증에서 키가 맞아떨어진다.
    private static final DateTimeFormatter BUCKET_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00");

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
    void getStatistics는_상태분포_쿼리_결과를_변환해서_distribution에_담는다() {
        IssueStatusCount count = mock(IssueStatusCount.class);

        when(issueMessageRepository.findThroughputByHour(any(), any())).thenReturn(List.of());
        when(issueMessageRepository.countGroupedByStatus()).thenReturn(List.of(count));

        var distributionResponse = com.mycom.petcoupon.coupon.dto.res.IssueStatusDistributionResponse.builder()
                .status(IssueMessageStatus.CONSUMED).count(1).build();
        when(issueStatisticsConverter.toDistributionResponse(count)).thenReturn(distributionResponse);

        IssueStatisticsResponse response = issueStatisticsService.getStatistics();

        assertThat(response.distribution()).containsExactly(distributionResponse);
    }

    @Test
    void getStatistics는_캐시_TTL_안에서는_상태분포_전체_집계를_한번만_수행한다() {
        when(issueMessageRepository.findThroughputByHour(any(), any())).thenReturn(List.of());
        when(issueMessageRepository.countGroupedByStatus()).thenReturn(List.of());

        issueStatisticsService.getStatistics();
        issueStatisticsService.getStatistics();

        verify(issueMessageRepository, times(1)).countGroupedByStatus();
        // 시간대별 추이는 캐시 대상이 아니므로 호출마다 최신 24시간 범위를 다시 읽는다.
        verify(issueMessageRepository, times(2)).findThroughputByHour(any(), any());
    }

    // [PR 리뷰 반영] GROUP BY 쿼리 특성상 요청이 0건인 시간대는 결과에서 통째로 빠진다.
    // 서비스가 이를 24개 정각 슬롯으로 zero-filling하는지 검증한다.
    //
    // findThroughputByHour에 실제로 넘어간 from(실행 시점의 now() 기준이라 테스트에서
    // 미리 알 수 없다)을 thenAnswer로 그 자리에서 캡처해, 변환기 스텁이 같은 값을 쓰게
    // 만든다 — 정각 경계를 테스트 시작 시점과 서비스 실행 시점에 각각 따로 계산해
    // 비교했다면 그 사이에 시가 바뀌는 극히 드문 경우 값이 어긋날 수 있는데, 이 방식은
    // 그 틈이 없다.
    @Test
    void getStatistics는_쿼리_결과가_없는_시간대를_0건으로_채운다() {
        IssueThroughputBucket bucket = mock(IssueThroughputBucket.class);
        AtomicReference<LocalDateTime> capturedFrom = new AtomicReference<>();

        when(issueMessageRepository.findThroughputByHour(any(), any())).thenAnswer(invocation -> {
            capturedFrom.set(invocation.getArgument(0));
            return List.of(bucket);
        });
        when(issueStatisticsConverter.toBucketResponse(bucket)).thenAnswer(invocation ->
                IssueThroughputBucketResponse.builder()
                        .bucket(capturedFrom.get().format(BUCKET_FORMATTER))
                        .issuedCount(3)
                        .failedCount(1)
                        .inProgressCount(2)
                        .build()
        );
        when(issueMessageRepository.countGroupedByStatus()).thenReturn(List.of());

        IssueStatisticsResponse response = issueStatisticsService.getStatistics();

        assertThat(response.timeSeries()).hasSize(24);

        IssueThroughputBucketResponse firstBucket = response.timeSeries().get(0);
        assertThat(firstBucket.bucket()).isEqualTo(capturedFrom.get().format(BUCKET_FORMATTER));
        assertThat(firstBucket.issuedCount()).isEqualTo(3);
        assertThat(firstBucket.failedCount()).isEqualTo(1);
        assertThat(firstBucket.inProgressCount()).isEqualTo(2);

        // 실제 데이터가 있던 맨 앞 슬롯을 제외한 나머지 23개는 전부 0건으로 채워져야 한다.
        assertThat(response.timeSeries().subList(1, 24)).allSatisfy(zeroBucket -> {
            assertThat(zeroBucket.issuedCount()).isZero();
            assertThat(zeroBucket.failedCount()).isZero();
            assertThat(zeroBucket.inProgressCount()).isZero();
        });

        // 24개 bucket 문자열이 정각 단위로 중복 없이 연속돼야 한다.
        List<String> expectedBuckets = java.util.stream.Stream.iterate(capturedFrom.get(), h -> h.plusHours(1))
                .limit(24)
                .map(h -> h.format(BUCKET_FORMATTER))
                .toList();
        assertThat(response.timeSeries())
                .extracting(IssueThroughputBucketResponse::bucket)
                .containsExactlyElementsOf(expectedBuckets);
    }
}
