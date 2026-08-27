package com.mycom.petcoupon.coupon.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.IssueStatisticsConverter;
import com.mycom.petcoupon.coupon.dto.res.IssueStatisticsResponse;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;

// 발급 처리량/상태 분포 조회(#156) — 대시보드가 그래프+도넛을 한 화면에 같이 그리는
// 경우가 대부분이라, 두 레포지토리 쿼리(findThroughputByHour/countGroupedByStatus)의
// 결과를 한 응답으로 묶어 반환한다. "오늘 제외할 것"(사용자 지정 시간 단위)에 따라
// timeSeries 범위는 최근 24시간으로 고정한다 — 파라미터를 안 받는다.
@Service
@RequiredArgsConstructor
public class IssueStatisticsService {

    private static final Duration TIME_SERIES_WINDOW = Duration.ofHours(24);

    private final IssueMessageRepository issueMessageRepository;
    private final IssueStatisticsConverter issueStatisticsConverter;

    public IssueStatisticsResponse getStatistics() {
        LocalDateTime since = LocalDateTime.now().minus(TIME_SERIES_WINDOW);

        return IssueStatisticsResponse.builder()
                .timeSeries(issueMessageRepository.findThroughputByHour(since).stream()
                        .map(issueStatisticsConverter::toBucketResponse)
                        .toList())
                .distribution(issueMessageRepository.countGroupedByStatus().stream()
                        .map(issueStatisticsConverter::toDistributionResponse)
                        .toList())
                .build();
    }
}
