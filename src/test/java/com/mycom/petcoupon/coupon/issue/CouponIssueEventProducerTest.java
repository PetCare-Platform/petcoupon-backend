package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueEventProducer;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CouponIssueEventProducerTest {

	private static final CouponIssueEvent EVENT = new CouponIssueEvent(
		1L, 10L, "request-1", 5L, "COUPON-CODE-1", LocalDateTime.now().plusDays(7)
	);

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Mock
	private IssueMessageRepository issueMessageRepository;

	@Mock
	private JsonMapper jsonMapper;

	@Mock
	private IssueMessage issueMessage;

	private CouponIssueEventProducer producer;

	@BeforeEach
	void setUp() {
		// whenCompleteAsync는 별도 executor에서 콜백을 돌리는데, 테스트에서 비동기로 두면
		// verify() 시점에 콜백이 아직 안 끝났을 수 있어 그 자리에서 바로 실행하는 executor를 씀
		producer = new CouponIssueEventProducer(kafkaTemplate, issueMessageRepository, jsonMapper, Runnable::run);
	}

	@Test
	void 발행에_성공하면_SENT로_상태를_갱신한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(EVENT.requestId()), eq(EVENT)))
			.thenReturn(CompletableFuture.completedFuture(null));

		producer.publish(issueMessage);

		verify(issueMessageRepository).updateStatus(1L, IssueMessageStatus.SENT);
	}

	@Test
	void 비동기_발행이_실패하면_FAILED로_상태를_갱신한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(EVENT.requestId()), eq(EVENT)))
			.thenReturn(failed);

		producer.publish(issueMessage);

		verify(issueMessageRepository).updateStatusWithError(eq(1L), eq(IssueMessageStatus.FAILED), any());
	}

	@Test
	void 발행_시도_자체가_동기_예외로_실패하면_FAILED로_갱신하고_예외를_전파한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(EVENT.requestId()), eq(EVENT)))
			.thenThrow(new RuntimeException("sync failure"));

		Throwable thrown = catchThrowable(() -> producer.publish(issueMessage));

		assertThat(thrown).isInstanceOf(RuntimeException.class);
		verify(issueMessageRepository).updateStatusWithError(eq(1L), eq(IssueMessageStatus.FAILED), any());
	}

	@Test
	void payload_파싱에_실패하면_FAILED로_갱신하고_예외를_전파한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("not-json");
		when(jsonMapper.readValue("not-json", CouponIssueEvent.class))
			.thenThrow(new RuntimeException("malformed json"));

		Throwable thrown = catchThrowable(() -> producer.publish(issueMessage));

		assertThat(thrown).isInstanceOf(RuntimeException.class);
		verify(issueMessageRepository).updateStatusWithError(eq(1L), eq(IssueMessageStatus.FAILED), any());
		verify(kafkaTemplate, org.mockito.Mockito.never())
			.send(any(String.class), any(), any());
	}
}
