package com.mycom.petcoupon.reconciliation.scheduler;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.reconciliation.batch.service.ReconciliationJobTriggerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정합성 검증(#111)을 관리자가 수동으로 트리거하지 않아도 주기적으로 자동 실행한다(#154).
 *
 * PreconditionCheckTasklet이 ENDED 상태가 아니면 바로 거절하므로, 애초에 ENDED 쿠폰만
 * 대상으로 순회한다. 간격은 코드에 고정하지 않고 프로퍼티로 빼서, 운영은 기본값(30분)을 쓰고
 * 테스트/시연에서는 짧은 값으로 오버라이드할 수 있게 한다 — 실제로 몇십 분을 기다리며
 * 검증할 방법이 없기 때문이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "coupon.reconciliation.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final CouponRepository couponRepository;
    private final ReconciliationJobTriggerService reconciliationJobTriggerService;

    @Scheduled(
            fixedDelayString = "${coupon.reconciliation.schedule-fixed-delay-ms:1800000}",
            initialDelayString = "${coupon.reconciliation.schedule-initial-delay-ms:1800000}",
            scheduler = "reconciliationSchedulerTaskScheduler"
    )
    public void runScheduledReconciliation() {
        List<Long> endedCouponIds = couponRepository.findCouponIdsByStatus(CouponStatus.ENDED);

        for (Long couponId : endedCouponIds) {
            try {
                reconciliationJobTriggerService.reconcile(couponId);
            } catch (Exception e) {
                // 한 쿠폰의 실행 중(REQUEST_IN_PROGRESS로 이미 도는 중이거나, 파이프라인
                // 미드레인 등) 예외가 나머지 쿠폰들의 검증을 막으면 안 된다 — 실패한 쿠폰은
                // 다음 주기에 다시 시도된다.
                log.error("[ReconciliationScheduler] couponId={} 자동 정합성 검증 실패", couponId, e);
            }
        }
    }
}
