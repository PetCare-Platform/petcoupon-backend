package com.mycom.petcoupon.coupon.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;

class CouponIssueConverterTest {

    @Test
    void toCreateResponse_필드가_올바르게_매핑된다() {

        CouponIssueCreateResponse response = CouponIssueConverter.toCreateResponse(10L, 20L);

        assertThat(response.couponId()).isEqualTo(10L);
        assertThat(response.userId()).isEqualTo(20L);
    }
}