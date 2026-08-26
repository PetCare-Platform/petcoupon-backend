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
		log.info(
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
			log.info(
				"[CouponIssueEvent] 저장완료 requestId={} sequenceNo={}",
				event.requestId(), event.sequenceNo()
			);

			// 알림 기록은 발급 확정과 별도 트랜잭션이라 여기서 실패해도 삼킨다 — 이미 커밋된 발급을
			// 되돌릴 수도 없고, 그대로 던지면 원인(예: phone null)과 무관하게 Kafka 재시도가
			// 영원히 반복돼 정상 처리된 발급까지 DLQ로 밀려나게 된다.
			try {
				couponIssuePersister.recordNotification(couponIssue);
			} catch (Exception notificationException) {
				log.error(
					"[CouponIssueEvent] 알림 로그 기록 실패, 발급 자체는 정상 처리됨: requestId={}",
					event.requestId(), notificationException
				);
			}
		} catch (DataIntegrityViolationException e) {
			Optional<CouponIssue> racedByOtherConsumer = couponIssueRepository.findByRequestId(event.requestId());
			if (racedByOtherConsumer.isPresent()) {
				// 이 requestId로 이미 저장이 끝난 상태 (재전달) — 재고는 정상 소진된 것이므로 보상하지 않음
				log.warn(
					"[CouponIssueEvent] 재전달로 인한 저장 스킵 (이미 처리된 것으로 확인): requestId={}",
					event.requestId(), e
				);
				couponIssuePersister.confirmIdempotencySucceeded(racedByOtherConsumer.get(), event.requestId());
				couponIssuePersister.markConsumed(event.requestId());
			} else {
				// requestId 충돌이 아닌 다른 제약 위반 (예: 존재하지 않는 coupon/user FK) — 저장은 안 됐으므로 재고 보상 필요
				// TODO: CouponIssueLuaService.restoreStock()이 아직 없어 실제 재고 보상 호출 불가 (작업 대기)
				// 여기서 삼키면 오프셋이 정상 커밋돼 재시도/DLQ 경로를 아예 안 타므로 재전파해서 그 경로를 타게 함
				throw e;
			}
		}
		// 그 외 예외는 그대로 던져서 DefaultErrorHandler의 재시도(FixedBackOff) 대상이 되도록 함
	}
}
