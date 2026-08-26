package com.mycom.petcoupon.coupon.issue.config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.consumer.CouponIssuePendingMessageRecoverer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis Stream Pending 메시지를 주기적으로 회수한다.
 *
 * 앱 전체 @Scheduled TaskScheduler와 분리하여 Outbox, 이벤트 상태 전이, 쿠폰 만료 스케줄러와 서로 실행을
 * 지연시키지 않도록 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
	prefix = "coupon.issue.stream",
	name = {
		"enabled",
		"pending-recovery.enabled"
	},  
	havingValue = "true",
	matchIfMissing = true
)
public class CouponIssuePendingRecoveryScheduler {

	private final TaskScheduler taskScheduler;
	private final CouponIssueStreamProperties properties;
	private final CouponIssuePendingMessageRecoverer recoverer;

	private ScheduledFuture<?> scheduledTask;

	public CouponIssuePendingRecoveryScheduler(
			@Qualifier("redisStreamPendingRecoveryTaskScheduler") TaskScheduler taskScheduler,
			CouponIssueStreamProperties properties, CouponIssuePendingMessageRecoverer recoverer
	) {
		this.taskScheduler = taskScheduler;
		this.properties = properties;
		this.recoverer = recoverer;
	}

	@PostConstruct
	public void start() {
		
		Duration fixedDelay = properties.getPendingRecovery().getFixedDelay();

		if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
			throw new IllegalStateException("Pending recovery fixedDelay는 0보다 커야 합니다.");
		}

		// 앱 시작과 동시에 실행하면 Consumer Group 생성보다 먼저 실행될 수 있다. 첫 실행도 fixedDelay만큼 기다린 후 시작한다.
		scheduledTask = taskScheduler.scheduleWithFixedDelay(this::runSafely, Instant.now().plus(fixedDelay), fixedDelay);
	}

	private void runSafely() {
		try {
			int recoveredCount = recoverer.recoverPendingMessages();

			if (recoveredCount > 0) {
				log.info("Redis Stream Pending 메시지 회수 및 재처리 요청 완료. claimedCount={}", recoveredCount);
			}

		} catch (Throwable t) {
			
			// 한 번의 Redis 장애나 재처리 실패로 스케줄러 자체가 종료되지 않게 한다. 메시지는 ACK되지 않았으므로 다음 주기에 다시 회수할 수 있다.
			log.error("Redis Stream Pending 메시지 회수 중 오류가 발생했습니다. " + "다음 주기에 재시도합니다.", t);
		}
	}

	@PreDestroy
	public void stop() {
		if (scheduledTask != null) {
			scheduledTask.cancel(false);
		}
	}
}
