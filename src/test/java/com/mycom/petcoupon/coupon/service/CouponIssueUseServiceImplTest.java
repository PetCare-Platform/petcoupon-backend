package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponIssueHistory;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponIssueHistoryRepository;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.entity.AppUser;

@ExtendWith(MockitoExtension.class)
class CouponIssueUseServiceImplTest {

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponIssueHistoryRepository couponIssueHistoryRepository;

    @InjectMocks
    private CouponIssueUseServiceImpl couponIssueUseService;

    @Test
    void useMarksCouponIssueAsUsedAndSavesHistory() {
        Long couponIssueId = 1L;
        Long userId = 100L;

        AppUser owner = mock(AppUser.class);
        when(owner.getUserId()).thenReturn(userId);

        Coupon coupon = mock(Coupon.class);
        when(coupon.getCouponId()).thenReturn(10L);

        CouponIssue couponIssue = CouponIssue.builder()
                .coupon(coupon)
                .user(owner)
                .status(IssueStatus.ISSUED)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();

        when(couponIssueRepository.findById(couponIssueId)).thenReturn(Optional.of(couponIssue));
        when(couponIssueRepository.updateStatusIfMatches(
                eq(couponIssueId), eq(IssueStatus.ISSUED), eq(IssueStatus.USED), any(LocalDateTime.class)
        )).thenReturn(1);

        couponIssueUseService.use(couponIssueId, userId);

        verify(couponIssueHistoryRepository).save(any(CouponIssueHistory.class));
    }

    @Test
    void useThrowsExceptionWhenCouponIssueNotFound() {
        when(couponIssueRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponIssueUseService.use(999L, 1L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_ISSUE_NOT_FOUND);
    }

    @Test
    void useThrowsExceptionWhenNotOwner() {
        Long couponIssueId = 1L;

        AppUser owner = mock(AppUser.class);
        when(owner.getUserId()).thenReturn(100L);

        CouponIssue couponIssue = CouponIssue.builder()
                .user(owner)
                .status(IssueStatus.ISSUED)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();

        when(couponIssueRepository.findById(couponIssueId)).thenReturn(Optional.of(couponIssue));

        assertThatThrownBy(() -> couponIssueUseService.use(couponIssueId, 999L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.NOT_COUPON_OWNER);

        verify(couponIssueHistoryRepository, never()).save(any());
    }

    @Test
    void useThrowsExceptionWhenAlreadyUsed() {
        Long couponIssueId = 1L;
        Long userId = 100L;

        AppUser owner = mock(AppUser.class);
        when(owner.getUserId()).thenReturn(userId);

        Coupon coupon = mock(Coupon.class);
        when(coupon.getCouponId()).thenReturn(10L);

        CouponIssue couponIssue = CouponIssue.builder()
                .coupon(coupon)
                .user(owner)
                .status(IssueStatus.USED)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();

        when(couponIssueRepository.findById(couponIssueId)).thenReturn(Optional.of(couponIssue));
        when(couponIssueRepository.updateStatusIfMatches(
                eq(couponIssueId), eq(IssueStatus.ISSUED), eq(IssueStatus.USED), any(LocalDateTime.class)
        )).thenReturn(0);

        assertThatThrownBy(() -> couponIssueUseService.use(couponIssueId, userId))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.INVALID_ISSUE_STATUS);

        verify(couponIssueHistoryRepository, never()).save(any());
    }
}
