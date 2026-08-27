package com.mycom.petcoupon.dashboard.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockSummary;
import com.mycom.petcoupon.dashboard.converter.DashboardSummaryConverter;
import com.mycom.petcoupon.dashboard.dto.res.DashboardSummaryResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.repository.EventRepository;

import lombok.RequiredArgsConstructor;

// 대시보드 요약 집계(#172) — 이벤트/쿠폰/발급결과 레벨 요약을 한 응답으로 묶는다. #156처럼
// 시간 범위·zero-filling 같은 복잡한 로직 없이 단순 집계 4개를 모아서 반환한다.
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
        // (CouponStockRepository.sumStock() 참고).
        CouponStockSummary stockSummary = couponStockRepository.sumStock();
        long totalStock = stockSummary.getTotalQuantity();
        long issuedStock = stockSummary.getIssuedQuantity();
        long remainingStock = stockSummary.getRemainingQuantity();
        // totalStock이 0이면(대상 쿠폰이 아직 하나도 없으면, 즉 전부 READY거나 쿠폰 자체가
        // 없으면) 0으로 나누게 되므로 0.0으로 고정한다 — NaN을 그대로 내려보내면 JSON
        // 직렬화/프론트 그래프 쪽에서 문제가 된다(DashboardSummaryResponse의 issueRate 주석 참고).
        double issueRate = totalStock == 0 ? 0.0 : (double) issuedStock / totalStock;

        return DashboardSummaryResponse.builder()
                .totalEvents(totalEvents)
                .activeEvents(activeEvents)
                .totalCoupons(totalCoupons)
                .activeCoupons(activeCoupons)
                .totalStock(totalStock)
                .issuedStock(issuedStock)
                .remainingStock(remainingStock)
                .issueRate(issueRate)
                .couponIssueStatusDistribution(couponIssueRepository.countGroupedByStatus().stream()
                        .map(dashboardSummaryConverter::toDistributionResponse)
                        .toList())
                .build();
    }
}
