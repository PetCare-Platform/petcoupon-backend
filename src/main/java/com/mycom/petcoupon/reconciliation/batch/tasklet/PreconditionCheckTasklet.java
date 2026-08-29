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
 * Step0 — 정합성 검증 사전조건 체크. 더 발급될 수 없는 상태가 아니거나 파이프라인이 아직
 * 드레인 안 됐으면 예외를 던져 Job을 즉시 실패시킨다.
 *
 * <p>전제는 "발급 기간이 끝났는가"가 아니라 "더 이상 발급될 수 없는가"다. 그래서 ENDED뿐 아니라
 * SOLD_OUT도 통과시킨다(#202) — 재고가 0이면 Lua가 전건 거절하므로 새 발급이 생길 수 없고, 이미
 * 나간 요청의 뒤늦은 확정은 아래 드레인 체크가 막는다. ENDED만 허용하던 때는 발급 기간이 한 달
 * 남은 쿠폰을 재고를 다 써도 그때까지 검증할 수 없었다.
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

        if (!CouponStatus.isReconcilable(coupon.getStatus())) {
            throw new GeneralException(CouponErrorCode.RECONCILIATION_NOT_ALLOWED_YET);
        }

        PipelineDrainStatus drainStatus = pipelineDrainChecker.check(couponId);
        if (drainStatus.isBlocked()) {
            throw new GeneralException(CouponErrorCode.RECONCILIATION_PIPELINE_NOT_DRAINED);
        }

        return RepeatStatus.FINISHED;
    }
}
