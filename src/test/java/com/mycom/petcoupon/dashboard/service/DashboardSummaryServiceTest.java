package com.mycom.petcoupon.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponIssueStatusCount;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockSummary;
import com.mycom.petcoupon.dashboard.converter.DashboardSummaryConverter;
import com.mycom.petcoupon.dashboard.dto.res.CouponIssueStatusDistributionResponse;
import com.mycom.petcoupon.dashboard.dto.res.DashboardSummaryResponse;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.repository.EventRepository;

@ExtendWith(MockitoExtension.class)
class DashboardSummaryServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private DashboardSummaryConverter dashboardSummaryConverter;

    @InjectMocks
    private DashboardSummaryService dashboardSummaryService;

    @Test
    void getSummary는_이벤트_쿠폰_집계를_각_필드에_그대로_담는다() {
        when(eventRepository.count()).thenReturn(10L);
        when(eventRepository.countByStatus(EventStatus.OPEN)).thenReturn(4L);
        when(couponRepository.count()).thenReturn(20L);
        when(couponRepository.countByStatus(CouponStatus.ACTIVE)).thenReturn(7L);

        CouponStockSummary stockSummary = mock(CouponStockSummary.class);
        when(stockSummary.getTotalQuantity()).thenReturn(1000L);
        when(stockSummary.getIssuedQuantity()).thenReturn(300L);
        when(stockSummary.getRemainingQuantity()).thenReturn(700L);
        when(couponStockRepository.sumStock()).thenReturn(stockSummary);

        when(couponIssueRepository.countGroupedByStatus()).thenReturn(List.of());

        DashboardSummaryResponse response = dashboardSummaryService.getSummary();

        assertThat(response.totalEvents()).isEqualTo(10L);
        assertThat(response.activeEvents()).isEqualTo(4L);
        assertThat(response.totalCoupons()).isEqualTo(20L);
        assertThat(response.activeCoupons()).isEqualTo(7L);
        assertThat(response.totalStock()).isEqualTo(1000L);
        assertThat(response.issuedStock()).isEqualTo(300L);
        assertThat(response.remainingStock()).isEqualTo(700L);
    }

    @Test
    void getSummary는_issueRate를_issuedStock_나누기_totalStock으로_계산한다() {
        when(eventRepository.count()).thenReturn(0L);
        when(eventRepository.countByStatus(EventStatus.OPEN)).thenReturn(0L);
        when(couponRepository.count()).thenReturn(0L);
        when(couponRepository.countByStatus(CouponStatus.ACTIVE)).thenReturn(0L);

        CouponStockSummary stockSummary = mock(CouponStockSummary.class);
        when(stockSummary.getTotalQuantity()).thenReturn(200L);
        when(stockSummary.getIssuedQuantity()).thenReturn(50L);
        when(stockSummary.getRemainingQuantity()).thenReturn(150L);
        when(couponStockRepository.sumStock()).thenReturn(stockSummary);

        when(couponIssueRepository.countGroupedByStatus()).thenReturn(List.of());

        DashboardSummaryResponse response = dashboardSummaryService.getSummary();

        assertThat(response.issueRate()).isEqualTo(0.25);
    }

    // [설계 의도] totalStock이 0이면(쿠폰이 하나도 없으면) 0으로 나누게 되는데, 그걸 그대로
    // NaN으로 내보내지 않고 0.0으로 고정하는지 확인한다 — DashboardSummaryResponse 주석 참고.
    @Test
    void getSummary는_totalStock이_0이면_issueRate를_0으로_고정한다() {
        when(eventRepository.count()).thenReturn(0L);
        when(eventRepository.countByStatus(EventStatus.OPEN)).thenReturn(0L);
        when(couponRepository.count()).thenReturn(0L);
        when(couponRepository.countByStatus(CouponStatus.ACTIVE)).thenReturn(0L);

        CouponStockSummary stockSummary = mock(CouponStockSummary.class);
        when(stockSummary.getTotalQuantity()).thenReturn(0L);
        when(stockSummary.getIssuedQuantity()).thenReturn(0L);
        when(stockSummary.getRemainingQuantity()).thenReturn(0L);
        when(couponStockRepository.sumStock()).thenReturn(stockSummary);

        when(couponIssueRepository.countGroupedByStatus()).thenReturn(List.of());

        DashboardSummaryResponse response = dashboardSummaryService.getSummary();

        assertThat(response.issueRate()).isEqualTo(0.0);
        assertThat(response.issueRate()).isNotNaN();
    }

    @Test
    void getSummary는_발급건_상태_분포를_변환기를_통해_매핑한다() {
        when(eventRepository.count()).thenReturn(0L);
        when(eventRepository.countByStatus(EventStatus.OPEN)).thenReturn(0L);
        when(couponRepository.count()).thenReturn(0L);
        when(couponRepository.countByStatus(CouponStatus.ACTIVE)).thenReturn(0L);

        CouponStockSummary stockSummary = mock(CouponStockSummary.class);
        when(stockSummary.getTotalQuantity()).thenReturn(0L);
        when(stockSummary.getIssuedQuantity()).thenReturn(0L);
        when(stockSummary.getRemainingQuantity()).thenReturn(0L);
        when(couponStockRepository.sumStock()).thenReturn(stockSummary);

        CouponIssueStatusCount issuedCount = mock(CouponIssueStatusCount.class);
        when(couponIssueRepository.countGroupedByStatus()).thenReturn(List.of(issuedCount));

        var distributionResponse = CouponIssueStatusDistributionResponse.builder()
                .status(IssueStatus.ISSUED).count(5).build();
        when(dashboardSummaryConverter.toDistributionResponse(issuedCount)).thenReturn(distributionResponse);

        DashboardSummaryResponse response = dashboardSummaryService.getSummary();

        assertThat(response.couponIssueStatusDistribution()).containsExactly(distributionResponse);
    }
}
