package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.IssueStatisticsConverter;
import com.mycom.petcoupon.coupon.dto.res.IssueStatisticsResponse;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;

// 발급 처리량/상태 분포 조회(#156) — 대시보드가 그래프+도넛을 한 화면에 같이 그리는
// 경우가 대부분이라, 두 레포지토리 쿼리(findThroughputByHour/countGroupedByStatus)의
// 결과를 한 응답으로 묶어 반환한다. 시간 단위를 사용자가 고를 수 있게 하는 대신 스코프를
// 단순하게 유지하려고 timeSeries 범위는 최근 24시간으로 고정한다 — 파라미터를 안 받는다.
@Service
@RequiredArgsConstructor
public class IssueStatisticsService {

    private static final int TIME_SERIES_HOURS = 24;

    private final IssueMessageRepository issueMessageRepository;
    private final IssueStatisticsConverter issueStatisticsConverter;

    public IssueStatisticsResponse getStatistics() {
        // [PR 리뷰 반영] now()를 그대로 24시간 빼면 정각에 안 맞아서 맨 앞 버킷이 부분치만
        // 담긴다(예: 21:30에 조회하면 첫 버킷은 21:30~22:00 30분치뿐인데, 나머지는 온전한
        // 1시간치라 그래프에서 그 시간대만 유독 낮아 보임). 현재 시각을 정각으로 잘라서
        // [currentHour-23시간, currentHour+1시간) 범위로 넘기면, 맨 앞부터 항상 정각 단위
        // 24개 버킷이 나온다 — 맨 뒤(진행 중인 이번 시간) 하나만 자연스럽게 덜 찬 상태로 남는다.
        LocalDateTime currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = currentHour.minusHours(TIME_SERIES_HOURS - 1);
        LocalDateTime to = currentHour.plusHours(1);

        return IssueStatisticsResponse.builder()
                .timeSeries(issueMessageRepository.findThroughputByHour(from, to).stream()
                        .map(issueStatisticsConverter::toBucketResponse)
                        .toList())
                .distribution(issueMessageRepository.countGroupedByStatus().stream()
                        .map(issueStatisticsConverter::toDistributionResponse)
                        .toList())
                .build();
    }
}
