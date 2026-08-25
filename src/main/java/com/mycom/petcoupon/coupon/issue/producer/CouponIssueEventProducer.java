package com.mycom.petcoupon.coupon.issue.producer;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventProducer {

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final IssueMessageRepository issueMessageRepository;
	private final JsonMapper jsonMapper;

	// Executor 타입 빈이 여러 개(redisStreamRecoveryTaskScheduler 등)라 @Qualifier로 특정함.
	// 프로젝트 루트 lombok.config에 등록해둬서 @RequiredArgsConstructor가 이 어노테이션을 생성자로 복사해줌
	@Qualifier("kafkaCallbackExecutor")
	private final Executor kafkaCallbackExecutor;

	public void publish(IssueMessage issueMessage) {
		CouponIssueEvent event = null;

		try {
			final CouponIssueEvent parsedEvent = jsonMapper.readValue(issueMessage.getPayload(), CouponIssueEvent.class);
			event = parsedEvent;

			// whenComplete는 기본적으로 Kafka producer I/O 스레드에서 실행되므로,
			// 그 안의 블로킹 DB 호출이 발행 파이프라인을 지연시키지 않도록 별도 executor로 뺌
			// 파티션 키는 requestId(요청 단위)가 아니라 couponId여야 함 — 같은 쿠폰의 이벤트가
			// 항상 같은 파티션으로 가야 sequenceNo 순서가 컨슈머 처리 순서와 일치함(Kafka는 파티션 내에서만 순서 보장)
			kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT, String.valueOf(parsedEvent.couponId()), parsedEvent)
				.whenCompleteAsync((result, ex) -> {
					// whenCompleteAsync가 반환하는 새 Future를 아무도 지켜보지 않으므로,
					// 콜백 내부(markSent/markFailed의 DB 호출 등)에서 예외가 나면 로그도 없이 그냥 사라짐 — 직접 잡아서 남김
					try {
						if (ex != null) {
							log.error("[CouponIssueEvent] 발행 실패: requestId={}", parsedEvent.requestId(), ex);
							markFailed(issueMessage, parsedEvent, ex);
						} else {
							log.info("[CouponIssueEvent] 발행 성공: requestId={}", parsedEvent.requestId());
							markSent(issueMessage);
						}
					} catch (Exception callbackException) {
						log.error(
							"[CouponIssueEvent] 발행 완료 콜백 처리 중 예외 발생, 수동 확인 필요: messageId={}, requestId={}",
							issueMessage.getMessageId(), parsedEvent.requestId(), callbackException
						);
					}
				}, kafkaCallbackExecutor);
		} catch (RuntimeException e) {
			// payload 파싱 실패(event==null) 또는 send()가 Future를 반환하기 전 동기 예외로 실패한 경우 —
			// 어느 쪽이든 issue_message는 FAILED로 남겨야 하고, 호출자가 WAITING 상태를 유지하지 않도록 예외를 그대로 전파
			log.error("[CouponIssueEvent] 발행 처리 실패: messageId={}", issueMessage.getMessageId(), e);
			if (event != null) {
				restoreStock(event);
			}
			issueMessageRepository.updateStatusWithError(
				issueMessage.getMessageId(), IssueMessageStatus.FAILED, e.getMessage()
			);
			throw e;
		}
	}

	private void markSent(IssueMessage issueMessage) {
		issueMessageRepository.updateStatus(issueMessage.getMessageId(), IssueMessageStatus.SENT);
	}

	private void markFailed(IssueMessage issueMessage, CouponIssueEvent event, Throwable ex) {
		restoreStock(event);
		issueMessageRepository.updateStatusWithError(
			issueMessage.getMessageId(), IssueMessageStatus.FAILED, ex.getMessage()
		);
	}

	// TODO: CouponIssueLuaService.restoreStock()이 아직 없어 실제 재고 보상 호출 불가 (작업 대기)
	private void restoreStock(CouponIssueEvent event) {
		log.warn(
			"[CouponIssueEvent] 발행 실패로 재고 보상이 필요하지만 아직 구현되지 않음. couponId={}, userId={}, requestId={}",
			event.couponId(), event.userId(), event.requestId()
		);
	}
}
