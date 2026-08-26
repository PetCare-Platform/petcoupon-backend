package com.mycom.petcoupon.coupon.issue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueEventRecoverer;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CouponIssueEventRecovererTest {

	private static final CouponIssueEvent EVENT = new CouponIssueEvent(
		1L, 10L, "request-1", 5L, "COUPON-CODE-1", LocalDateTime.now().plusDays(7)
	);

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Mock
	private IssueMessageRepository issueMessageRepository;

	@Mock
	private IdempotencyKeyService idempotencyKeyService;

	@Mock
	private ObjectMapper objectMapper;

	@InjectMocks
	private CouponIssueEventRecoverer recoverer;

	@Test
	void 이벤트_역직렬화에_성공하면_DLQ_토픽에_발행하고_상태를_갱신한다() {
		when(issueMessageRepository.markDlq(
			KafkaTopics.COUPON_ISSUE_EVENT, "request-1", IssueMessageStatus.DLQ, "consume failed"
		)).thenReturn(1);

		ConsumerRecord<String, Object> record =
			new ConsumerRecord<>(KafkaTopics.COUPON_ISSUE_EVENT, 0, 0L, "request-1", EVENT);

		recoverer.accept(record, new RuntimeException("consume failed"));

		verify(kafkaTemplate).send(eq(KafkaTopics.COUPON_ISSUE_EVENT_DLQ), eq("request-1"), eq(EVENT));
		verify(issueMessageRepository).markDlq(
			eq(KafkaTopics.COUPON_ISSUE_EVENT), eq("request-1"), eq(IssueMessageStatus.DLQ), any()
		);
	}

	@Test
	void issue_message를_찾지_못해도_DLQ_토픽_발행은_그대로_한다() {
		when(issueMessageRepository.markDlq(
			eq(KafkaTopics.COUPON_ISSUE_EVENT), eq("request-1"), eq(IssueMessageStatus.DLQ), any()
		)).thenReturn(0);

		ConsumerRecord<String, Object> record =
			new ConsumerRecord<>(KafkaTopics.COUPON_ISSUE_EVENT, 0, 0L, "request-1", EVENT);

		recoverer.accept(record, new RuntimeException("consume failed"));

		verify(kafkaTemplate).send(eq(KafkaTopics.COUPON_ISSUE_EVENT_DLQ), eq("request-1"), eq(EVENT));
	}

	@Test
	void markDlq_처리중_예외가_나도_DLQ_토픽_발행은_시도한다() {
		when(issueMessageRepository.markDlq(
			eq(KafkaTopics.COUPON_ISSUE_EVENT), eq("request-1"), eq(IssueMessageStatus.DLQ), any()
		)).thenThrow(new RuntimeException("db down"));

		ConsumerRecord<String, Object> record =
			new ConsumerRecord<>(KafkaTopics.COUPON_ISSUE_EVENT, 0, 0L, "request-1", EVENT);

		recoverer.accept(record, new RuntimeException("consume failed"));

		verify(kafkaTemplate).send(eq(KafkaTopics.COUPON_ISSUE_EVENT_DLQ), eq("request-1"), eq(EVENT));
	}

	@Test
	void 이벤트_역직렬화에_실패하면_아무_동작도_하지_않는다() {
		ConsumerRecord<String, Object> record =
			new ConsumerRecord<>(KafkaTopics.COUPON_ISSUE_EVENT, 0, 0L, "request-1", "malformed-payload");

		recoverer.accept(record, new RuntimeException("deserialization failed"));

		verifyNoInteractions(kafkaTemplate, issueMessageRepository, idempotencyKeyService);
	}

	@Test
	void requestId가_issue_형식이면_idempotency_key를_FAILED로_확정한다() {
		CouponIssueEvent event = new CouponIssueEvent(
			1L, 10L, "issue:42", 5L, "COUPON-CODE-1", LocalDateTime.now().plusDays(7)
		);
		when(objectMapper.writeValueAsString(any())).thenReturn("{\"isSuccess\":false}");

		ConsumerRecord<String, Object> record =
			new ConsumerRecord<>(KafkaTopics.COUPON_ISSUE_EVENT, 0, 0L, "issue:42", event);

		recoverer.accept(record, new RuntimeException("consume failed"));

		verify(idempotencyKeyService).fail(
			eq(42L), eq(CouponErrorCode.ISSUE_CONFIRMATION_FAILED.getStatus().value()), eq("{\"isSuccess\":false}")
		);
	}

	@Test
	void requestId가_issue_형식이_아니면_idempotency_key를_건드리지_않는다() {
		ConsumerRecord<String, Object> record =
			new ConsumerRecord<>(KafkaTopics.COUPON_ISSUE_EVENT, 0, 0L, "request-1", EVENT);

		recoverer.accept(record, new RuntimeException("consume failed"));

		verifyNoInteractions(idempotencyKeyService);
	}
}
