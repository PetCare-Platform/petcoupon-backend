package com.mycom.petcoupon.coupon.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.IssueStatisticsConverter;
import com.mycom.petcoupon.coupon.dto.res.IssueStatisticsResponse;
import com.mycom.petcoupon.coupon.dto.res.IssueStatusDistributionResponse;
import com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;

// 발급 처리량/상태 분포 조회(#156) — 대시보드가 그래프+도넛을 한 화면에 같이 그리는
// 경우가 대부분이라, 두 레포지토리 쿼리(findThroughputByHour/countGroupedByStatus)의
// 결과를 한 응답으로 묶어 반환한다. 시간 단위를 사용자가 고를 수 있게 하는 대신 스코프를
// 단순하게 유지하려고 timeSeries 범위는 최근 24시간으로 고정한다 — 파라미터를 안 받는다.
//
// [PR 리뷰 반영] 조회 전용이라 readOnly=true — 스냅샷/더티체킹 오버헤드를 끄고, 두 쿼리가
// 같은 트랜잭션 안에서 실행되게 해 시점이 갈라지는 걸 줄인다. 같은 패키지의 다른 조회
// 서비스(CouponQueryServiceImpl 등)도 전부 이 패턴을 쓴다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueStatisticsService {

    private static final int TIME_SERIES_HOURS = 24;

    // findThroughputByHour의 네이티브 쿼리가 DATE_FORMAT(created_at, '%Y-%m-%d %H:00:00')로
    // bucket을 만드는 것과 반드시 같은 포맷이어야 한다 — zero-filling 때 채워 넣는 bucket
    // 문자열이 DB가 실제로 반환하는 bucket 문자열과 일치해야 매칭이 되기 때문이다.
    private static final DateTimeFormatter BUCKET_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00");

    private final IssueMessageRepository issueMessageRepository;
    private final IssueStatisticsConverter issueStatisticsConverter;

    // 전체 issue_message를 GROUP BY 하는 상태 분포만 짧게 캐시한다. 시간대별 추이는 매 요청마다
    // 최신 24시간 범위로 계산해야 하므로 캐시하지 않는다. 운영 환경에서는 30~60초 범위로 둔다.
    @Value("${coupon.issue.statistics.distribution-cache-ttl:PT30S}")
    private Duration distributionCacheTtl = Duration.ofSeconds(30);

    private volatile CachedDistribution cachedDistribution;

    public IssueStatisticsResponse getStatistics() {
        // [PR 리뷰 반영] now()를 그대로 24시간 빼면 정각에 안 맞아서 맨 앞 버킷이 부분치만
        // 담긴다(예: 21:30에 조회하면 첫 버킷은 21:30~22:00 30분치뿐인데, 나머지는 온전한
        // 1시간치라 그래프에서 그 시간대만 유독 낮아 보임). 현재 시각을 정각으로 잘라서
        // [currentHour-23시간, currentHour+1시간) 범위로 넘기면, 맨 앞부터 항상 정각 단위
        // 24개 버킷이 나온다 — 맨 뒤(진행 중인 이번 시간) 하나만 자연스럽게 덜 찬 상태로 남는다.
        // to는 buildTimeSeries가 from + 24시간으로 직접 계산한다(zero-filling 슬롯 생성과
        // 쿼리 range를 같은 from 하나로 맞추기 위해 여기서 별도로 안 만든다).
        LocalDateTime currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = currentHour.minusHours(TIME_SERIES_HOURS - 1);

        return IssueStatisticsResponse.builder()
                .timeSeries(buildTimeSeries(from))
                .distribution(getCachedStatusDistribution())
                .build();
    }

    private List<IssueStatusDistributionResponse> getCachedStatusDistribution() {
        Instant now = Instant.now();
        CachedDistribution cached = cachedDistribution;
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.distribution();
        }

        synchronized (this) {
            cached = cachedDistribution;
            if (cached != null && now.isBefore(cached.expiresAt())) {
                return cached.distribution();
            }

            List<IssueStatusDistributionResponse> distribution = issueMessageRepository
                    .countGroupedByStatus().stream()
                    .map(issueStatisticsConverter::toDistributionResponse)
                    .toList();
            cachedDistribution = new CachedDistribution(distribution, now.plus(distributionCacheTtl));
            return distribution;
        }
    }

    // 프론트가 24개 연속 배열을 기대하고 그리는 차트(막대·라인)라면 빈 시간대가 사라진 채로
    // 넘어가면 x축 간격이 어긋난다 — 그래서 여기서 정각 단위 24슬롯을 전부 만들고, 쿼리
    // 결과가 있는 슬롯만 채운 뒤 나머지는 0건으로 채워 넣는다(zero-filling).
    private List<IssueThroughputBucketResponse> buildTimeSeries(LocalDateTime from) {
        Map<String, IssueThroughputBucketResponse> bucketsByKey = issueMessageRepository
                .findThroughputByHour(from, from.plusHours(TIME_SERIES_HOURS))
                .stream()
                .map(issueStatisticsConverter::toBucketResponse)
                .collect(Collectors.toMap(IssueThroughputBucketResponse::bucket, Function.identity()));

        return Stream.iterate(from, hour -> hour.plusHours(1))
                .limit(TIME_SERIES_HOURS)
                // getOrDefault(key, emptyBucket(hour))로 쓰면 키가 있어도 자바 인자 평가 규칙상
                // emptyBucket(hour)가 매번 먼저 만들어졌다가 버려진다 — get()으로 먼저 조회해
                // 없을 때만 만든다.
                .map(hour -> {
                    IssueThroughputBucketResponse existing = bucketsByKey.get(hour.format(BUCKET_FORMATTER));
                    return existing != null ? existing : emptyBucket(hour);
                })
                .toList();
    }

    private IssueThroughputBucketResponse emptyBucket(LocalDateTime hour) {
        return IssueThroughputBucketResponse.builder()
                .bucket(hour.format(BUCKET_FORMATTER))
                .issuedCount(0L)
                .failedCount(0L)
                .inProgressCount(0L)
                .build();
    }

    private record CachedDistribution(
            List<IssueStatusDistributionResponse> distribution,
            Instant expiresAt
    ) {
    }
}
