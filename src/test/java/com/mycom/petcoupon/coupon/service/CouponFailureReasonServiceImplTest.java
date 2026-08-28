package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.dto.res.CouponFailureReasonResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.idempotency.repository.IdempotencyRejectionCounts;
import com.mycom.petcoupon.messaging.entity.enums.IssueFailureReason;
import com.mycom.petcoupon.messaging.repository.IssueFailureReasonCount;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

@ExtendWith(MockitoExtension.class)
class CouponFailureReasonServiceImplTest {

    private static final Long COUPON_ID = 1L;

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private IssueMessageRepository issueMessageRepository;

    @InjectMocks
    private CouponFailureReasonServiceImpl couponFailureReasonService;

    private static IdempotencyRejectionCounts rejectionCounts(long soldOut, long alreadyIssued) {
        return new IdempotencyRejectionCounts() {
            @Override
            public long getSoldOut() {
                return soldOut;
            }

            @Override
            public long getAlreadyIssued() {
                return alreadyIssued;
            }
        };
    }

    private static IssueFailureReasonCount failureReasonCount(IssueFailureReason reason, long count) {
        return new IssueFailureReasonCount() {
            @Override
            public IssueFailureReason getFailureReason() {
                return reason;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    @Test
    void getFailureReasons_returnsCounts_whenCouponExists() {
        when(couponStockRepository.existsById(COUPON_ID)).thenReturn(true);
        when(idempotencyKeyRepository.countRejectionsByCouponId(
                COUPON_ID, CouponErrorCode.SOLD_OUT.getCode(), CouponErrorCode.DUPLICATE_USER.getCode()
        )).thenReturn(rejectionCounts(3, 1));
        when(issueMessageRepository.countDlqGroupedByFailureReasonForCoupon(COUPON_ID)).thenReturn(List.of(
                failureReasonCount(IssueFailureReason.KAFKA_PUBLISH_FAILED, 2),
                failureReasonCount(IssueFailureReason.CONSUME_PROCESSING_FAILED, 1),
                // 컬럼 도입 전 DLQ 행 — null 사유는 무시돼야 한다
                failureReasonCount(null, 5)
        ));

        CouponFailureReasonResponse response = couponFailureReasonService.getFailureReasons(COUPON_ID);

        assertThat(response.rejections().soldOut()).isEqualTo(3);
        assertThat(response.rejections().alreadyIssued()).isEqualTo(1);
        assertThat(response.failures().kafkaPublishFailed()).isEqualTo(2);
        assertThat(response.failures().consumeProcessingFailed()).isEqualTo(1);
    }

    @Test
    void getFailureReasons_throwsException_whenCouponNotFound() {
        when(couponStockRepository.existsById(COUPON_ID)).thenReturn(false);

        assertThatThrownBy(() -> couponFailureReasonService.getFailureReasons(COUPON_ID))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }
}
