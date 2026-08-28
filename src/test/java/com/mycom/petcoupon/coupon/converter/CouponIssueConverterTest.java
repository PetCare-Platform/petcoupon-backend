package com.mycom.petcoupon.coupon.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.user.entity.AppUser;

class CouponIssueConverterTest {

    private final CouponIssueConverter converter = new CouponIssueConverter();

    @Test
    void toCreateResponse_파라미터_기반_매핑_확인() {
        CouponIssueCreateResponse response = converter.toCreateResponse(10L, 20L);

        assertThat(response.couponId()).isEqualTo(10L);
        assertThat(response.userId()).isEqualTo(20L);
        assertThat(response.status()).isEqualTo("WAITING");
    }

    @Test
    void toCreateResponse_CouponIssue_엔티티가_올바르게_매핑된다() {
        Coupon coupon = Coupon.builder().build();
        ReflectionTestUtils.setField(coupon, "couponId", 10L);

        AppUser user = AppUser.builder().build();
        ReflectionTestUtils.setField(user, "userId", 20L);

        CouponIssue couponIssue = CouponIssue.builder()
                .coupon(coupon)
                .user(user)
                .sequenceNo(1L)
                .status(IssueStatus.ISSUED)
                .build();
        ReflectionTestUtils.setField(couponIssue, "couponIssueId", 100L);

        CouponIssueCreateResponse response = converter.toCreateResponse(couponIssue);

        assertThat(response.couponIssueId()).isEqualTo(100L);
        assertThat(response.couponId()).isEqualTo(10L);
        assertThat(response.userId()).isEqualTo(20L);
        assertThat(response.sequenceNo()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("ISSUED");
    }
}