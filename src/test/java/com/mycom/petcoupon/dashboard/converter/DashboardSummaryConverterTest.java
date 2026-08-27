package com.mycom.petcoupon.dashboard.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.repository.CouponIssueStatusCount;
import com.mycom.petcoupon.dashboard.dto.res.CouponIssueStatusDistributionResponse;

class DashboardSummaryConverterTest {

    private final DashboardSummaryConverter converter = new DashboardSummaryConverter();

    @Test
    void toDistributionResponse는_프로젝션_필드를_그대로_매핑한다() {
        CouponIssueStatusCount count = mock(CouponIssueStatusCount.class);
        when(count.getStatus()).thenReturn(IssueStatus.USED);
        when(count.getCount()).thenReturn(9L);

        CouponIssueStatusDistributionResponse response = converter.toDistributionResponse(count);

        assertThat(response.status()).isEqualTo(IssueStatus.USED);
        assertThat(response.count()).isEqualTo(9L);
    }
}
