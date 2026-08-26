package com.mycom.petcoupon.coupon.issue.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.idempotency.service.IdempotencyRequestIdCodec;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventRecoverer implements ConsumerRecordRecoverer {

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final IssueMessageRepository issueMessageRepository;
	private final IdempotencyKeyService idempotencyKeyService;
	private final ObjectMapper objectMapper;

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

		// 세 단계는 서로 독립적인 부수효과라, 하나가 실패해도 나머지는 최대한 시도되도록 각각 감쌈
		try {
			restoreStock(event);
		} catch (Exception e) {
			log.error("[CouponIssueEvent] 재고 보상 처리 중 예외 발생: requestId={}", event.requestId(), e);
		}

		try {
			markDlq(event, exception);
		} catch (Exception e) {
			log.error("[CouponIssueEvent] issue_message DLQ 상태 갱신 중 예외 발생: requestId={}", event.requestId(), e);
		}

		try {
			failIdempotency(event);
		} catch (Exception e) {
			log.error("[CouponIssueEvent] idempotency_key FAILED 확정 중 예외 발생: requestId={}", event.requestId(), e);
		}

		try {
			publishToDlqTopic(event);
		} catch (Exception e) {
			log.error("[CouponIssueEvent] DLQ 토픽 발행 중 예외 발생, 수동 확인 필요: requestId={}", event.requestId(), e);
		}
	}

	private void publishToDlqTopic(CouponIssueEvent event) {
		kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT_DLQ, event.requestId(), event);
	}

	private void markDlq(CouponIssueEvent event, Exception exception) {
		int updatedRows = issueMessageRepository.markDlq(
			KafkaTopics.COUPON_ISSUE_EVENT, event.requestId(), IssueMessageStatus.DLQ, exception.getMessage()
		);

		if (updatedRows == 0) {
			log.error(
				"[CouponIssueEvent] issue_message row를 찾지 못해 DLQ 상태 갱신 불가: requestId={}", event.requestId()
			);
		}
	}

	// 재시도가 모두 소진돼 DLQ로 넘어간 최종 실패 — 여기서 확정하지 않으면 idempotency_key가 접수 시점
	// SUCCEEDED("WAITING")에 영원히 머물러서, GET .../status를 폴링하는 클라이언트가 결과를 영영 못 받는다.
	// requestId가 "issue:" 형식이 아니면(Producer 직접 호출 등) idempotency_key 자체가 없으므로 건너뛴다.
	private void failIdempotency(CouponIssueEvent event) {
		IdempotencyRequestIdCodec.tryDecode(event.requestId()).ifPresent(recordId -> {
			CustomResponse<Void> failure = CustomResponse.onFailure(CouponErrorCode.ISSUE_CONFIRMATION_FAILED);
			idempotencyKeyService.fail(
				recordId,
				CouponErrorCode.ISSUE_CONFIRMATION_FAILED.getStatus().value(),
				objectMapper.writeValueAsString(failure)
			);
		});
	}

	// 여기서는 재고를 복구하지 않는다 — DLQ로 전이돼도 관리자가 CouponIssueDlqAdminController의
	// reprocess API로 이 메시지를 다시 살릴 수 있어, 여기서 즉시 복구하면 나중에 재처리가 성공했을 때
	// 초과발급으로 이어질 수 있다(재고는 복구됐는데 원래 요청도 뒤늦게 성공). 재고 복구는 관리자가
	// abandon API로 재처리를 포기한다고 명시적으로 결정했을 때만 실행된다.
	private void restoreStock(CouponIssueEvent event) {
		log.warn(
			"[CouponIssueEvent] 최종 실패로 DLQ 확정, 재고는 아직 보상하지 않음(관리자 abandon 결정 대기). "
				+ "couponId={}, userId={}, requestId={}",
			event.couponId(), event.userId(), event.requestId()
		);
	}
}
