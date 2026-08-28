package com.mycom.petcoupon.dashboard.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockSummary;
import com.mycom.petcoupon.dashboard.converter.DashboardSummaryConverter;
import com.mycom.petcoupon.dashboard.dto.res.CouponIssueStatusDistributionResponse;
import com.mycom.petcoupon.dashboard.dto.res.DashboardSummaryResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.repository.EventRepository;

import lombok.RequiredArgsConstructor;

// 대시보드 요약 집계(#172) — 이벤트/쿠폰/발급결과 레벨 요약을 한 응답으로 묶는다. #156처럼
// 시간 범위 계산 같은 복잡한 로직 없이 단순 집계 4개를 모아서 반환한다.
//
// 조회 전용이라 readOnly=true를 처음부터 붙인다(#156은 첫 구현 때 이걸 빠뜨렸다가 리뷰로
// 나중에 추가했다 — 같은 실수를 반복하지 않는다). 4개 레포지토리에 걸친 쿼리 6번이
// 전부 이 트랜잭션 안에서 실행돼 값을 읽는 시점이 서로 갈라지는 걸 줄인다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardSummaryService {

    private final EventRepository eventRepository;
    private final CouponRepository couponRepository;
    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final DashboardSummaryConverter dashboardSummaryConverter;

    public DashboardSummaryResponse getSummary() {
        long totalEvents = eventRepository.count();
        long activeEvents = eventRepository.countByStatus(EventStatus.OPEN);
        long totalCoupons = couponRepository.count();
        long activeCoupons = couponRepository.countByStatus(CouponStatus.ACTIVE);

        // sumStock()은 READY(발급 시작 전) 쿠폰을 뺀 ACTIVE·SOLD_OUT·ENDED만 합산한다
        // (CouponStockRepository.sumStock() 참고) — 그래서 아래 변수/필드명에 startedCoupon
        // 접두어를 붙인다.
        CouponStockSummary stockSummary = couponStockRepository.sumStock();
        long startedCouponTotalStock = stockSummary.getTotalQuantity();
        long startedCouponIssuedStock = stockSummary.getIssuedQuantity();
        long startedCouponRemainingStock = stockSummary.getRemainingQuantity();
        // startedCouponTotalStock이 0이면(대상 쿠폰이 아직 하나도 없으면, 즉 전부 READY거나
        // 쿠폰 자체가 없으면) 0으로 나누게 되므로 0.0으로 고정한다 — NaN을 그대로 내려보내면
        // JSON 직렬화/프론트 그래프 쪽에서 문제가 된다
        // (DashboardSummaryResponse의 startedCouponIssueRate 주석 참고).
        double startedCouponIssueRate = startedCouponTotalStock == 0
                ? 0.0
                : (double) startedCouponIssuedStock / startedCouponTotalStock;

        return DashboardSummaryResponse.builder()
                .totalEvents(totalEvents)
                .activeEvents(activeEvents)
                .totalCoupons(totalCoupons)
                .activeCoupons(activeCoupons)
                .startedCouponTotalStock(startedCouponTotalStock)
                .startedCouponIssuedStock(startedCouponIssuedStock)
                .startedCouponRemainingStock(startedCouponRemainingStock)
                .startedCouponIssueRate(startedCouponIssueRate)
                .couponIssueStatusDistribution(buildStatusDistribution())
                .build();
    }

    // [PR 리뷰 반영] countGroupedByStatus()는 GROUP BY라 실제로 발급 건이 있는 상태만
    // 반환한다 — 예를 들어 USED·EXPIRED가 아직 0건이면 결과에서 통째로 빠진다. 도넛/파이
    // 차트를 그리는 프론트가 3개 상태(ISSUED·USED·EXPIRED)가 항상 다 있길 기대할 수 있어서,
    // 여기서 IssueStatus.values() 전부를 순회하며 없는 상태는 0건으로 채운다. #156의
    // timeSeries zero-filling과 같은 문제인데, 시간 버킷과 달리 IssueStatus는 컴파일 타임에
    // 개수가 고정된 enum이라 정각 계산 없이 훨씬 단순하다.
    private List<CouponIssueStatusDistributionResponse> buildStatusDistribution() {
        Map<IssueStatus, CouponIssueStatusDistributionResponse> byStatus = couponIssueRepository
                .countGroupedByStatus()
                .stream()
                .map(dashboardSummaryConverter::toDistributionResponse)
                .collect(Collectors.toMap(CouponIssueStatusDistributionResponse::status, Function.identity()));

        // get()+null 체크로 조회한다 — getOrDefault(key, zeroDistribution(status))로 쓰면
        // 키가 있어도 자바 인자 평가 규칙상 zeroDistribution(status)가 매번 먼저 만들어졌다가
        // 버려진다(#156의 IssueStatisticsService.buildTimeSeries()에서 같은 걸 겪었다).
        return Arrays.stream(IssueStatus.values())
                .map(status -> {
                    CouponIssueStatusDistributionResponse existing = byStatus.get(status);
                    return existing != null ? existing : zeroDistribution(status);
                })
                .toList();
    }

    private CouponIssueStatusDistributionResponse zeroDistribution(IssueStatus status) {
        return CouponIssueStatusDistributionResponse.builder()
                .status(status)
                .count(0L)
                .build();
    }
}
