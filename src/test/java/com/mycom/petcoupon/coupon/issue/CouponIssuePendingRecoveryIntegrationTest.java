package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mycom.petcoupon.coupon.issue.consumer.CouponIssuePendingDlqHandler;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssuePendingMessageRecoverer;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueStreamConsumer;

@SpringBootTest(properties = {
	/*
	 * Listener Container와 실제 Pending Scheduler가 테스트 메시지를
	 * 먼저 가져가지 않도록 끈다. Recoverer는 테스트에서 직접 호출한다.
	 */
	"coupon.issue.stream.enabled=false",  
	"coupon.issue.stream.pending-recovery.enabled=false",
	"coupon.issue.stream.key=coupon:issue:stream:pending-test",
	"coupon.issue.stream.group=coupon-issue-pending-test-group",
	"coupon.issue.stream.consumer=recovery-test-consumer",
	
	"coupon.issue.stream.pending-recovery.max-delivery-count=3",
	"coupon.issue.stream.pending-recovery.dlq-key=coupon:issue:stream:pending-test:dlq",
	    
	/*
	 * 테스트에서 기다리지 않고 즉시 회수할 수 있도록 0으로 설정한다.
	 */
	"coupon.issue.stream.pending-recovery.min-idle-time=PT0S",
	"coupon.issue.stream.pending-recovery.batch-size=10",
	"coupon.issue.outbox.enabled=false",
	"spring.kafka.listener.auto-startup=false",  
	"event.status.scheduler.enabled=false",
	"coupon.status.enabled=false"
})
public class CouponIssuePendingRecoveryIntegrationTest {

	private static final String STREAM_KEY = "coupon:issue:stream:pending-test";
	private static final String GROUP = "coupon-issue-pending-test-group";
	private static final String DEAD_CONSUMER = "stopped-consumer";
	private static final String DLQ_KEY = "coupon:issue:stream:pending-test:dlq";
	
	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private CouponIssuePendingMessageRecoverer recoverer;
	
	@Autowired
	private CouponIssuePendingDlqHandler dlqHandler;

	/*
	 * 이 테스트의 목적은 실제 Redis XPENDING/XCLAIM/ACK 연동 검증이다. 비즈니스 처리 자체는
	 * CouponIssueStreamConsumerTest와 CouponIssuePipelineIntegrationTest에서 이미 검증하므로 Mock으로 교체한다.
	 */
	@MockitoBean
	private CouponIssueStreamConsumer streamConsumer;

	private StreamOperations<String, String, String> streamOperations;
	private RecordId pendingMessageId;

	@BeforeEach
	void setUp() {
		redisTemplate.delete(List.of(STREAM_KEY, DLQ_KEY));
		streamOperations = redisTemplate.opsForStream();

		MapRecord<String, String, String> record = MapRecord.create(STREAM_KEY,
				Map.of("requestId", "pending-recovery-request", "couponId", "1", "userId", "100"));

		pendingMessageId = streamOperations.add(record);

		assertThat(pendingMessageId).isNotNull();

		streamOperations.createGroup(STREAM_KEY, ReadOffset.from("0-0"), GROUP);

		/*
		 * 종료된 Consumer가 메시지를 읽은 상황을 만든다. auto ACK가 아니므로 이 메시지는 PEL에 남는다.
		 */
		List<MapRecord<String, String, String>> delivered = streamOperations.read(Consumer.from(GROUP, DEAD_CONSUMER),
				StreamReadOptions.empty().count(1), StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

		assertThat(delivered).isNotNull().hasSize(1);

		PendingMessagesSummary pendingBeforeRecovery = streamOperations.pending(STREAM_KEY, GROUP);

		assertThat(pendingBeforeRecovery.getTotalPendingMessages()).isEqualTo(1L);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(List.of(STREAM_KEY, DLQ_KEY));
	}

	@Test
	void Pending_메시지를_회수하여_기존_Consumer로_재처리하고_ACK한다() {
		
		// 실제 Consumer는 처리 성공 마지막 단계에서 ACK한다. 여기서는 Consumer 비즈니스 로직을 Mock으로 대체했으므로 호출되면 ACK하도록 동작을 지정한다.
		doAnswer(invocation -> {
			MapRecord<String, String, String> claimedMessage = invocation.getArgument(0);

			Long acknowledgedCount = streamOperations.acknowledge(STREAM_KEY, GROUP, claimedMessage.getId());

			assertThat(acknowledgedCount).isEqualTo(1L);

			return null;
		}).when(streamConsumer).onMessage(any());

		int recoveredCount = recoverer.recoverPendingMessages();

		assertThat(recoveredCount).isEqualTo(1);

		verify(streamConsumer)
				.onMessage(org.mockito.ArgumentMatchers.argThat(message -> message.getId().equals(pendingMessageId)
						&& message.getValue().get("requestId").equals("pending-recovery-request")
						&& message.getValue().get("couponId").equals("1")
						&& message.getValue().get("userId").equals("100")));

		PendingMessagesSummary pendingAfterRecovery = streamOperations.pending(STREAM_KEY, GROUP);

		assertThat(pendingAfterRecovery.getTotalPendingMessages()).isZero();
	}
	
	@Test
	void 재처리해도_ACK되지_않으면_Pending에_남는다() {
	    
		// 아무 동작도 하지 않음 = ACK하지 않음
	    doAnswer(invocation -> null)
	            .when(streamConsumer)
	            .onMessage(any());

	    int recoveredCount = recoverer.recoverPendingMessages();

	    assertThat(recoveredCount).isEqualTo(1);
	    verify(streamConsumer).onMessage(any());
	    
	    PendingMessagesSummary pending = streamOperations.pending(STREAM_KEY, GROUP);

	    assertThat(pending.getTotalPendingMessages()).isEqualTo(1L);
	}
	
	@Test
	void 최대_처리_횟수에_도달하면_DLQ로_이동하고_원본을_ACK한다() {
		
		// 최초 XREADGROUP으로 deliveryCount=1인 상태다. 두 번 더 claim하여 총 처리 횟수를 3으로 만든다.
		streamOperations.claim(STREAM_KEY, GROUP, "failed-retry-consumer-1", Duration.ZERO, pendingMessageId);
		streamOperations.claim(STREAM_KEY, GROUP, "failed-retry-consumer-2", Duration.ZERO, pendingMessageId);

		int handledCount = recoverer.recoverPendingMessages();

		assertThat(handledCount).isEqualTo(1);

		// 제한에 도달했으므로 기존 Consumer를 다시 호출하지 않는다.
		verifyNoInteractions(streamConsumer);

		PendingMessagesSummary pendingAfterDlq = streamOperations.pending(STREAM_KEY, GROUP);

		assertThat(pendingAfterDlq.getTotalPendingMessages()).isZero();

		List<MapRecord<String, String, String>> dlqMessages = streamOperations.range(DLQ_KEY, Range.unbounded());

		assertThat(dlqMessages).hasSize(1);

		MapRecord<String, String, String> dlqMessage = dlqMessages.get(0);

		assertThat(dlqMessage.getValue()).containsEntry("requestId", "pending-recovery-request")
				.containsEntry("couponId", "1").containsEntry("userId", "100")
				.containsEntry("originalMessageId", pendingMessageId.getValue()).containsEntry("deliveryCount", "3")
				.containsEntry("reason", "MAX_DELIVERY_COUNT_EXCEEDED").containsKey("failedAt");
	}
	
	@Test
	void DLQ_저장에_실패하면_원본_메시지는_Pending에_남는다() {
		
		streamOperations.claim(STREAM_KEY, GROUP, "failed-retry-consumer-1", Duration.ZERO, pendingMessageId);
		streamOperations.claim(STREAM_KEY, GROUP, "failed-retry-consumer-2", Duration.ZERO, pendingMessageId);

		// DLQ key를 String 타입으로 만들어 XADD가 WRONGTYPE 오류를 발생시키도록 한다.
		redisTemplate.opsForValue().set(DLQ_KEY, "not-a-stream");

		int handledCount = recoverer.recoverPendingMessages();

		// DLQ 이동에 실패했으므로 완료 처리된 메시지는 없다.
		assertThat(handledCount).isZero();

		verifyNoInteractions(streamConsumer);

		PendingMessagesSummary pendingAfterFailure = streamOperations.pending(STREAM_KEY, GROUP);

		assertThat(pendingAfterFailure.getTotalPendingMessages()).isEqualTo(1L);
	}
	
	@Test
	void DLQ_이동_직전에_원본이_ACK되면_DLQ_기록을_남기지_않는다() {
		
		List<MapRecord<String, String, String>> sourceMessages = streamOperations.range(
			STREAM_KEY,
			Range.closed(pendingMessageId.getValue(), pendingMessageId.getValue())
		);

		assertThat(sourceMessages).hasSize(1);

		Long acknowledgedCount = streamOperations.acknowledge(STREAM_KEY, GROUP, pendingMessageId);

		assertThat(acknowledgedCount).isEqualTo(1L);

		dlqHandler.moveToDlq(sourceMessages.get(0), 3L);

		List<MapRecord<String, String, String>> dlqMessages = streamOperations.range(DLQ_KEY, Range.unbounded());

		assertThat(dlqMessages).isEmpty();

		PendingMessagesSummary pending = streamOperations.pending(STREAM_KEY, GROUP);

		assertThat(pending.getTotalPendingMessages()).isZero();
	}
}
