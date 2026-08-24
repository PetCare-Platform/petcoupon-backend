package com.mycom.petcoupon.coupon.issue.producer;

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

	public void publish(IssueMessage issueMessage) {
		CouponIssueEvent event = jsonMapper.readValue(issueMessage.getPayload(), CouponIssueEvent.class);

		try {
			kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT, event.requestId(), event)
				.whenComplete((result, ex) -> {
					if (ex != null) {
						log.error("[CouponIssueEvent] 발행 실패: requestId={}", event.requestId(), ex);
						markFailed(issueMessage, event, ex);
					} else {
						log.info("[CouponIssueEvent] 발행 성공: requestId={}", event.requestId());
						markSent(issueMessage);
					}
				});
		} catch (RuntimeException e) {
			// send()가 Future를 반환하기 전에 동기 예외를 던지면 whenComplete 콜백이 등록되지 않으므로
			// 여기서 직접 보상하고, 호출자가 WAITING 상태를 유지하지 않도록 예외를 그대로 전파
			log.error("[CouponIssueEvent] 발행 시도 자체가 동기 예외로 실패: requestId={}", event.requestId(), e);
			markFailed(issueMessage, event, e);
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
