package com.mycom.petcoupon.coupon.issue.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventRecoverer implements ConsumerRecordRecoverer {

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final IssueMessageRepository issueMessageRepository;

	@Override
	public void accept(ConsumerRecord<?, ?> record, Exception exception) {
		log.error(
			"[CouponIssueEvent] 재시도 소진, 최종 실패: partition={}, offset={}",
			record.partition(), record.offset(), exception
		);

		if (!(record.value() instanceof CouponIssueEvent event)) {
			log.error(
				"[CouponIssueEvent] 이벤트 역직렬화 실패로 재고 보상/DLQ 처리 불가, 수동 확인 필요: partition={}, offset={}",
				record.partition(), record.offset()
			);
			return;
		}

		restoreStock(event);
		markDlq(event, exception);
		publishToDlqTopic(event);
	}

	private void publishToDlqTopic(CouponIssueEvent event) {
		kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT_DLQ, event.requestId(), event);
	}

	private void markDlq(CouponIssueEvent event, Exception exception) {
		issueMessageRepository.findByMessageKey(event.requestId()).ifPresentOrElse(
			issueMessage -> issueMessageRepository.markDlq(
				issueMessage.getMessageId(), IssueMessageStatus.DLQ, exception.getMessage()
			),
			() -> log.error(
				"[CouponIssueEvent] issue_message row를 찾지 못해 DLQ 상태 갱신 불가: requestId={}", event.requestId()
			)
		);
	}

	// TODO: CouponIssueLuaService.restoreStock()이 아직 없어 실제 재고 보상 호출 불가 (작업 대기)
	private void restoreStock(CouponIssueEvent event) {
		log.warn(
			"[CouponIssueEvent] 최종 실패로 재고 보상이 필요하지만 아직 구현되지 않음. couponId={}, userId={}, requestId={}",
			event.couponId(), event.userId(), event.requestId()
		);
	}
}
