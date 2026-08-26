package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

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

	private static final int MAX_RETRY_COUNT = 5;

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
		ReflectionTestUtils.setField(producer, "maxRetryCount", MAX_RETRY_COUNT);
	}

	@Test
	void 발행에_성공하면_SENT로_상태를_갱신한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(String.valueOf(EVENT.couponId())), eq(EVENT)))
			.thenReturn(CompletableFuture.completedFuture(null));

		producer.publish(issueMessage).join();

		verify(issueMessageRepository).markSent(
			eq(1L),
			eq(IssueMessageStatus.SENT),
			any(LocalDateTime.class)
	    );
	}

	@Test
	void 비동기_발행이_실패하면_FAILED로_상태를_갱신한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(String.valueOf(EVENT.couponId())), eq(EVENT)))
			.thenReturn(failed);

		Throwable thrown = catchThrowable(() -> producer.publish(issueMessage).join());
		
		assertThat(thrown).isInstanceOf(CompletionException.class);

		verify(issueMessageRepository).markPublishFailed(eq(1L), eq(IssueMessageStatus.FAILED), eq("kafka down"));
	}

	@Test
	void 재시도_소진_직전이면_실패해도_FAILED로_상태를_갱신한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(issueMessage.getRetryCount()).thenReturn(MAX_RETRY_COUNT - 2);
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(String.valueOf(EVENT.couponId())), eq(EVENT)))
			.thenReturn(failed);

		catchThrowable(() -> producer.publish(issueMessage).join());

		verify(issueMessageRepository).markPublishFailed(eq(1L), eq(IssueMessageStatus.FAILED), eq("kafka down"));
	}

	@Test
	void 재시도가_소진되면_DLQ로_상태를_갱신한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(issueMessage.getRetryCount()).thenReturn(MAX_RETRY_COUNT - 1);
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(String.valueOf(EVENT.couponId())), eq(EVENT)))
			.thenReturn(failed);

		catchThrowable(() -> producer.publish(issueMessage).join());

		verify(issueMessageRepository).markPublishFailed(eq(1L), eq(IssueMessageStatus.DLQ), eq("kafka down"));
	}

	@Test
	void DLQ_수동_재처리가_실패하면_재시도_횟수와_무관하게_DLQ로_복귀한다() {
		// claimForReprocess는 retryCount만 올리고 status는 DLQ 그대로 두고 선점하므로,
		// 재처리 대상 issueMessage는 getStatus()가 여전히 DLQ임 — 이때는 아직 재시도가
		// 소진되지 않은 낮은 retryCount라도 FAILED로 새면 안 되고 DLQ로 복귀해야
		// Outbox Poller(PENDING/FAILED만 조회)의 자동 재시도 대상에 다시 걸리지 않는다
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(issueMessage.getStatus()).thenReturn(IssueMessageStatus.DLQ);
		when(issueMessage.getRetryCount()).thenReturn(0);
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("kafka down"));

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(String.valueOf(EVENT.couponId())), eq(EVENT)))
			.thenReturn(failed);

		catchThrowable(() -> producer.publish(issueMessage).join());

		verify(issueMessageRepository).markPublishFailed(eq(1L), eq(IssueMessageStatus.DLQ), eq("kafka down"));
	}

	@Test
	void 발행_시도_자체가_동기_예외로_실패하면_FAILED로_갱신하고_예외를_전파한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(String.valueOf(EVENT.couponId())), eq(EVENT)))
			.thenThrow(new RuntimeException("sync failure"));

		Throwable thrown = catchThrowable(() -> producer.publish(issueMessage).join());

		assertThat(thrown).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(RuntimeException.class);

		verify(issueMessageRepository).markPublishFailed(eq(1L), eq(IssueMessageStatus.FAILED), eq("sync failure"));
	}

	@Test
	void payload_파싱에_실패하면_FAILED로_갱신하고_예외를_전파한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("not-json");
		when(jsonMapper.readValue("not-json", CouponIssueEvent.class))
			.thenThrow(new RuntimeException("malformed json"));

		Throwable thrown = catchThrowable(() -> producer.publish(issueMessage).join());

		assertThat(thrown).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(RuntimeException.class);
		
		verify(issueMessageRepository).markPublishFailed(eq(1L), eq(IssueMessageStatus.FAILED), eq("malformed json"));
		
		verify(kafkaTemplate, never())
			.send(any(String.class), any(), any());
	}

	@Test
	void 발행_성공_후_상태_갱신이_실패하면_Future가_실패한다() {
		when(issueMessage.getMessageId()).thenReturn(1L);
		when(issueMessage.getPayload()).thenReturn("{}");
		when(jsonMapper.readValue("{}", CouponIssueEvent.class)).thenReturn(EVENT);

		when(kafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), eq(String.valueOf(EVENT.couponId())), eq(EVENT)))
			.thenReturn(CompletableFuture.completedFuture(null));

		when(issueMessageRepository.markSent(eq(1L), eq(IssueMessageStatus.SENT), any(LocalDateTime.class)))
			.thenThrow(new RuntimeException("db down"));

		Throwable thrown = catchThrowable(() -> producer.publish(issueMessage).join());

		assertThat(thrown)
			.isInstanceOf(CompletionException.class)
	        .hasCauseInstanceOf(RuntimeException.class);
		
		verify(issueMessageRepository).markSent(eq(1L), eq(IssueMessageStatus.SENT), any(LocalDateTime.class));
	}
}
