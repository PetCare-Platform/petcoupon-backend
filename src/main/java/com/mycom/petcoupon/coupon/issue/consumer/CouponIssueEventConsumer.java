package com.mycom.petcoupon.coupon.issue.consumer;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventConsumer {

	private final CouponIssueRepository couponIssueRepository;
	private final CouponIssuePersister couponIssuePersister;

	@KafkaListener(
		topics = KafkaTopics.COUPON_ISSUE_EVENT,
		containerFactory = "couponIssueKafkaListenerContainerFactory"
	)
	public void consume(CouponIssueEvent event) {
		log.debug(
			"[CouponIssueEvent] 수신: couponId={}, userId={}, requestId={}, sequenceNo={}",
			event.couponId(), event.userId(), event.requestId(), event.sequenceNo()
		);

		// 재전달된 메시지 (이전 처리에서 저장까지는 성공했으나 오프셋 커밋이 안 된 경우) — 재고 보상 없이 스킵.
		// 단, idempotency_key 확정(succeed)까지 끝났다는 보장은 없다(그 사이에 죽었을 수 있음) — 그래서 여기서도 다시 확정을 시도한다.
		Optional<CouponIssue> alreadyPersisted = couponIssueRepository.findByRequestId(event.requestId());
		if (alreadyPersisted.isPresent()) {
			log.warn("[CouponIssueEvent] 이미 저장된 requestId 재수신, 스킵: requestId={}", event.requestId());
			couponIssuePersister.confirmIdempotencySucceeded(alreadyPersisted.get(), event.requestId());
			couponIssuePersister.markConsumed(event.requestId());
			return;
		}

		try {
			CouponIssue couponIssue = couponIssuePersister.persist(event);
			log.debug(
				"[CouponIssueEvent] 저장완료 requestId={} sequenceNo={}",
				event.requestId(), event.sequenceNo()
			);

			// recordNotification()의 실패는 여기서만 삼킬 수 있다 — 메서드 내부 try/catch로는 막을 수
			// 없다는 게 실측으로 확인됐다(JPA 스펙상 flush 실패로 트랜잭션이 rollback-only로 마킹되면,
			// 메서드 안에서 예외를 잡아 삼켜도 프록시가 커밋을 시도하다가 UnexpectedRollbackException을
			// 새로 던진다 — CouponIssuePersister.recordNotification() 주석 참고). 이 안쪽 try/catch를
			// 없애고 바깥의 DataIntegrityViolationException 처리에 맡기면, persist() 재전달 감지
			// 로직이 알림 실패까지 "저장 중 제약 위반"으로 오인해서 엉뚱한 경로(재고 보상/DLQ 재시도)를
			// 타게 되므로 반드시 별도 catch로 분리해야 한다.
			try {
				couponIssuePersister.recordNotification(couponIssue);
			} catch (Exception notificationException) {
				log.error(
					"[CouponIssueEvent] 알림 로그 기록 실패, 발급 자체는 정상 처리됨: requestId={}",
					event.requestId(), notificationException
				);
			}
		} catch (DataIntegrityViolationException | org.springframework.dao.ConcurrencyFailureException e) {
			Optional<CouponIssue> racedByOtherConsumer = findWithRetry(event.requestId());
			if (racedByOtherConsumer.isPresent()) {
				// 이 requestId로 이미 저장이 끝난 상태 (재전달/동시 경합) — 재고는 정상 소진된 것이므로 보상하지 않음
				log.warn(
					"[CouponIssueEvent] 재전달로 인한 저장 스킵 (이미 처리된 것으로 확인): requestId={}",
					event.requestId(), e
				);
				couponIssuePersister.confirmIdempotencySucceeded(racedByOtherConsumer.get(), event.requestId());
				couponIssuePersister.markConsumed(event.requestId());
			} else {
				// requestId 충돌이 아닌 다른 제약 위반 (예: 존재하지 않는 coupon/user FK) — 저장은 안 됐으므로 재고 보상 필요.
				// 다만 여기서 즉시 복구하지 않는다 — 이 메시지도 결국 DLQ로 전이되고 관리자가 reprocess API로
				// 다시 살릴 수 있어, 즉시 복구하면 나중에 재처리가 성공했을 때 초과발급으로 이어질 수 있다.
				// 재고 복구는 관리자가 abandon API로 재처리를 포기했을 때만 실행된다.
				// 여기서 삼키면 오프셋이 정상 커밋돼 재시도/DLQ 경로를 아예 안 타므로 재전파해서 그 경로를 타게 함
				throw e;
			}
		}
		// 그 외 예외는 그대로 던져서 DefaultErrorHandler의 재시도(FixedBackOff) 대상이 되도록 함
	}

	private Optional<CouponIssue> findWithRetry(String requestId) {
		for (int i = 0; i < 5; i++) {
			Optional<CouponIssue> found = couponIssueRepository.findByRequestId(requestId);
			if (found.isPresent()) {
				return found;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return couponIssueRepository.findByRequestId(requestId);
	}
}
