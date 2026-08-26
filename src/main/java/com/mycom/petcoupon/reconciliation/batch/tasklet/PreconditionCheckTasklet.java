package com.mycom.petcoupon.reconciliation.batch.tasklet;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.service.CouponIssuePipelineDrainChecker;
import com.mycom.petcoupon.coupon.issue.service.PipelineDrainStatus;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/**
 * Step0 — 정합성 검증 사전조건 체크. ENDED 상태가 아니거나 파이프라인이 아직 드레인 안 됐으면
 * 예외를 던져 Job을 즉시 실패시킨다.
 */
@Component
@StepScope
@RequiredArgsConstructor
public class PreconditionCheckTasklet implements Tasklet {

    private final CouponRepository couponRepository;
    private final CouponIssuePipelineDrainChecker pipelineDrainChecker;

    @Value("#{jobParameters['couponId']}")
    private Long couponId;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        if (coupon.getStatus() != CouponStatus.ENDED) {
            throw new GeneralException(CouponErrorCode.RECONCILIATION_NOT_ALLOWED_YET);
        }

        PipelineDrainStatus drainStatus = pipelineDrainChecker.check(couponId);
        if (drainStatus.isBlocked()) {
            throw new GeneralException(CouponErrorCode.RECONCILIATION_PIPELINE_NOT_DRAINED);
        }

        return RepeatStatus.FINISHED;
    }
}
