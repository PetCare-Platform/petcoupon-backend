package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDetailResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@ExtendWith(MockitoExtension.class)
class CouponIssueQueryServiceImplTest {

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponIssueConverter couponIssueConverter;

    @InjectMocks
    private CouponIssueQueryServiceImpl couponIssueQueryService;

    @Test
    void getStatus_returnsUsableTrue_whenIssuedAndNotExpired() {
        CouponIssue couponIssue = CouponIssue.builder()
                .status(IssueStatus.ISSUED)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        CouponIssueStatusResponse expected = CouponIssueStatusResponse.builder()
                .status("ISSUED")
                .isUsable(true)
                .expiresAt(couponIssue.getExpiresAt())
                .build();

        when(couponIssueRepository.findById(1L)).thenReturn(Optional.of(couponIssue));
        when(couponIssueConverter.toStatusResponse(couponIssue, true)).thenReturn(expected);

        CouponIssueStatusResponse response = couponIssueQueryService.getStatus(1L);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void getStatus_throwsException_whenCouponIssueNotFound() {
        when(couponIssueRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponIssueQueryService.getStatus(999L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_ISSUE_NOT_FOUND);
    }

    @Test
    void getStatus_returnsUsableFalse_whenExpired() {
        CouponIssue couponIssue = CouponIssue.builder()
                .status(IssueStatus.ISSUED)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();

        when(couponIssueRepository.findById(2L)).thenReturn(Optional.of(couponIssue));
        when(couponIssueConverter.toStatusResponse(couponIssue, false)).thenReturn(
                CouponIssueStatusResponse.builder()
                        .status("ISSUED")
                        .isUsable(false)
                        .expiresAt(couponIssue.getExpiresAt())
                        .build()
        );

        CouponIssueStatusResponse response = couponIssueQueryService.getStatus(2L);

        assertThat(response.isUsable()).isFalse();
    }

    @Test
    void getDetail_returnsUsableTrue_whenIssuedAndNotExpired() {
        CouponIssue couponIssue = CouponIssue.builder()
                .couponCode("COUPON-0001")
                .status(IssueStatus.ISSUED)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        CouponIssueDetailResponse expected = CouponIssueDetailResponse.builder()
                .couponIssueId(couponIssue.getCouponIssueId())
                .couponCode(couponIssue.getCouponCode())
                .status("ISSUED")
                .isUsable(true)
                .usedAt(couponIssue.getUsedAt())
                .expiresAt(couponIssue.getExpiresAt())
                .createdAt(couponIssue.getCreatedAt())
                .build();

        when(couponIssueRepository.findById(1L)).thenReturn(Optional.of(couponIssue));
        when(couponIssueConverter.toDetailResponse(couponIssue, true)).thenReturn(expected);

        CouponIssueDetailResponse response = couponIssueQueryService.getDetail(1L);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void getDetail_throwsException_whenCouponIssueNotFound() {
        when(couponIssueRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponIssueQueryService.getDetail(999L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_ISSUE_NOT_FOUND);
    }

    @Test
    void getDetail_returnsUsableFalse_whenExpired() {
        CouponIssue couponIssue = CouponIssue.builder()
                .couponCode("COUPON-0002")
                .status(IssueStatus.ISSUED)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();

        when(couponIssueRepository.findById(2L)).thenReturn(Optional.of(couponIssue));
        when(couponIssueConverter.toDetailResponse(couponIssue, false)).thenReturn(
                CouponIssueDetailResponse.builder()
                        .couponIssueId(couponIssue.getCouponIssueId())
                        .couponCode(couponIssue.getCouponCode())
                        .status("ISSUED")
                        .isUsable(false)
                        .usedAt(couponIssue.getUsedAt())
                        .expiresAt(couponIssue.getExpiresAt())
                        .createdAt(couponIssue.getCreatedAt())
                        .build()
        );

        CouponIssueDetailResponse response = couponIssueQueryService.getDetail(2L);

        assertThat(response.isUsable()).isFalse();
    }
}