package com.mycom.petcoupon.messaging.scheduler;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REPROCESSING 선점 후 Kafka 발행 콜백이 영영 안 돌아오면 그 메시지는 REPROCESSING에 영구히
 * 갇혀 DLQ 목록·재처리·포기 어디에도 안 걸린다(#217 후속 리뷰). stale-after-ms보다 오래된
 * 행만 DLQ로 되돌린다 — Redis Stream의 pending 회수 스케줄러와 같은 패턴.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "coupon.issue.dlq.reprocess-recovery",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true
)
public class CouponIssueReprocessRecoveryScheduler {

	private static final String RECOVERY_LAST_ERROR = "관리자 재처리 선점 후 발행 결과가 도착하지 않아 자동으로 DLQ로 되돌림";

	private final IssueMessageRepository issueMessageRepository;

	@Value("${coupon.issue.dlq.reprocess-recovery.stale-after-ms:300000}")
	private long staleAfterMs;

	@Scheduled(
			fixedDelayString = "${coupon.issue.dlq.reprocess-recovery.fixed-delay-ms:60000}",
			scheduler = "reprocessRecoveryTaskScheduler"
	)
	public void recoverStaleReprocessingMessages() {
		try {
			LocalDateTime cutoff = LocalDateTime.now().minusNanos(staleAfterMs * 1_000_000L);

			int recovered = issueMessageRepository.recoverStaleReprocessingMessages(cutoff, RECOVERY_LAST_ERROR);

			if (recovered > 0) {
				log.warn(
						"[CouponIssueReprocessRecovery] 정체된 REPROCESSING 메시지를 DLQ로 복구했습니다. count={}, staleAfterMs={}",
						recovered, staleAfterMs
				);
			}
		} catch (Exception e) {
			// 이번 주기 실패로 스케줄러 자체가 멈추지 않게 한다 — 다음 주기에 다시 시도한다.
			log.error("[CouponIssueReprocessRecovery] REPROCESSING 정체 복구 작업 중 예외가 발생했습니다.", e);
		}
	}
}
