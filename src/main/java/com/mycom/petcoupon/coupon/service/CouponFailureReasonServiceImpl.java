package com.mycom.petcoupon.coupon.service;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.dto.res.CouponFailureReasonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponFailureReasonResponse.Failures;
import com.mycom.petcoupon.coupon.dto.res.CouponFailureReasonResponse.Rejections;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.idempotency.repository.IdempotencyRejectionCounts;
import com.mycom.petcoupon.messaging.entity.enums.IssueFailureReason;
import com.mycom.petcoupon.messaging.repository.IssueFailureReasonCount;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;

// 실패 사유 분류 집계(#195). rejections/failures 둘 다 요청받은 원안 그대로가 아니다 —
// CouponFailureReasonResponse 주석에 그 이유(EVENT_NOT_OPEN/EVENT_CLOSED 미저장, failures
// 5분류가 실제 코드에 없음)를 적어뒀다.
@Service
@RequiredArgsConstructor
public class CouponFailureReasonServiceImpl implements CouponFailureReasonService {

    private final CouponStockRepository couponStockRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final IssueMessageRepository issueMessageRepository;

    @Override
    @Transactional(readOnly = true)
    public CouponFailureReasonResponse getFailureReasons(Long couponId) {
        if (!couponStockRepository.existsById(couponId)) {
            throw new GeneralException(CouponErrorCode.COUPON_NOT_FOUND);
        }

        IdempotencyRejectionCounts rejectionCounts = idempotencyKeyRepository.countRejectionsByCouponId(
                couponId, CouponErrorCode.SOLD_OUT.getCode(), CouponErrorCode.DUPLICATE_USER.getCode()
        );

        Map<IssueFailureReason, Long> failureCounts = issueMessageRepository.countDlqGroupedByFailureReasonForCoupon(couponId)
                .stream()
                // 이 컬럼이 생기기 전 DLQ 행은 failureReason이 null이다 — 어느 사유에도 안 잡히는 게 맞다.
                .filter(count -> count.getFailureReason() != null)
                .collect(Collectors.toMap(IssueFailureReasonCount::getFailureReason, IssueFailureReasonCount::getCount));

        return CouponFailureReasonResponse.builder()
                .rejections(Rejections.builder()
                        .soldOut(rejectionCounts.getSoldOut())
                        .alreadyIssued(rejectionCounts.getAlreadyIssued())
                        .build())
                .failures(Failures.builder()
                        .kafkaPublishFailed(failureCounts.getOrDefault(IssueFailureReason.KAFKA_PUBLISH_FAILED, 0L))
                        .consumeProcessingFailed(failureCounts.getOrDefault(IssueFailureReason.CONSUME_PROCESSING_FAILED, 0L))
                        .build())
                .build();
    }
}
