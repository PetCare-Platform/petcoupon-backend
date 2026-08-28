package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.dto.res.CouponLoadTestStatusResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponIssueLoadTestSummary;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.messaging.repository.IssueStatusCount;

@ExtendWith(MockitoExtension.class)
class CouponLoadTestStatusServiceImplTest {

    private static final Long COUPON_ID = 1L;

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private IssueMessageRepository issueMessageRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @InjectMocks
    private CouponLoadTestStatusServiceImpl couponLoadTestStatusService;

    private static IssueStatusCount issueStatusCount(IssueMessageStatus status, long count) {
        return new IssueStatusCount() {
            @Override
            public IssueMessageStatus getStatus() {
                return status;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    private static CouponIssueLoadTestSummary summary(long passed, long duplicateUsers, boolean sequenceIntact, Long elapsedSeconds) {
        return new CouponIssueLoadTestSummary() {
            @Override
            public long getPassedCount() {
                return passed;
            }

            @Override
            public long getDuplicateUserCount() {
                return duplicateUsers;
            }

            @Override
            public boolean getSequenceIntact() {
                return sequenceIntact;
            }

            @Override
            public Long getElapsedSeconds() {
                return elapsedSeconds;
            }
        };
    }

    @Test
    void getLoadTestStatus_returnsResponse_whenCouponExists() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(100).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of(
                issueStatusCount(IssueMessageStatus.CONSUMED, 8),
                issueStatusCount(IssueMessageStatus.DLQ, 1)
        ));
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(10L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(8, 0, true, 12L));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.accepted()).isEqualTo(10);
        assertThat(response.passed()).isEqualTo(8);
        assertThat(response.rejected()).isEqualTo(2);
        assertThat(response.consumed()).isEqualTo(8);
        assertThat(response.dlq()).isEqualTo(1);
        assertThat(response.pending()).isZero();
        assertThat(response.overIssued()).isFalse();
        assertThat(response.sequenceIntact()).isTrue();
        assertThat(response.elapsedSeconds()).isEqualTo(12);
    }

    @Test
    void getLoadTestStatus_throwsException_whenCouponNotFound() {
        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponLoadTestStatusService.getLoadTestStatus(COUPON_ID))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void getLoadTestStatus_defaultsElapsedSecondsToZero_whenNoIssuesYet() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(100).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of());
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(0, 0, true, null));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.elapsedSeconds()).isZero();
    }

    @Test
    void getLoadTestStatus_marksOverIssued_whenPassedExceedsExpected() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(5).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of());
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(5L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        // 총재고(5)·접수(5) 모두 5인데 실제 발급이 6건 — 초과발급 상황
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(6, 0, true, 3L));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.overIssued()).isTrue();
    }
}
