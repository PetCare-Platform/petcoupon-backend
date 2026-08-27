package com.mycom.petcoupon.dashboard.dto.res;

import java.util.List;

import lombok.Builder;

// 대시보드 요약 집계(#172) — 관리자 대시보드 첫 화면 상단 요약 카드용 단일 응답(리스트 아님).
//
// #156(발급 처리량/메시지 상태 분포, issue_message 기준)·#170(인프라 컴포넌트 상태)과
// 성격이 겹치지 않게, 이벤트/쿠폰/발급결과(coupon_issue) 레벨 요약만 담당한다.
//
// totalStock/issuedStock/remainingStock/issueRate는 READY(발급 시작 전) 쿠폰을 뺀
// ACTIVE·SOLD_OUT·ENDED 쿠폰만 합산한 값이다 — READY는 issuedQuantity가 항상 0이라
// 포함시키면 실제 수요와 무관하게 발급률이 낮아 보인다(CouponStockRepository.sumStock()
// 참고). activeCoupons도 READY를 뺀다는 점은 같지만, 범위가 더 좁다 — ACTIVE만 세고
// SOLD_OUT·ENDED는 안 세므로, "재고 합계에 잡히는 쿠폰 수"와 activeCoupons는 다른 값이다.
//
// issueRate는 totalStock 대비 issuedStock 비율(0.0~1.0)이다. 대상 쿠폰이 하나도 없어
// totalStock이 0이면 0/0이 되는데, 이 경우 DashboardSummaryService가 0.0으로 고정한다
// (NaN을 그대로 내려보내면 JSON 직렬화 시 문제가 되거나 프론트가 그래프를 못 그린다).
@Builder
public record DashboardSummaryResponse(
        long totalEvents,
        long activeEvents,
        long totalCoupons,
        long activeCoupons,
        long totalStock,
        long issuedStock,
        long remainingStock,
        double issueRate,
        List<CouponIssueStatusDistributionResponse> couponIssueStatusDistribution
) {
}
