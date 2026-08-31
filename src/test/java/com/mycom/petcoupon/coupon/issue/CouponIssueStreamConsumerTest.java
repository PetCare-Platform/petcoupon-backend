package com.mycom.petcoupon.coupon.issue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueStreamConsumer;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.messaging.service.CouponIssueOutboxService;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
public class CouponIssueStreamConsumerTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private StreamOperations<String, Object, Object> streamOperations;

	@Mock
	private CouponIssueStreamProperties properties;

	@Mock
    private CouponIssueLuaService couponIssueLuaService;

    @Mock
    private CouponIssueOutboxService couponIssueOutboxService;

    @Mock
    private IdempotencyKeyService idempotencyKeyService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private PendingMessages pendingMessages;
    
	private CouponIssueStreamConsumer consumer;

	@BeforeEach
	void setUp() {
		consumer = new CouponIssueStreamConsumer(redisTemplate, properties, couponIssueLuaService, couponIssueOutboxService, idempotencyKeyService, objectMapper);
	}

	@Test
	void 정상적인_메시지를_처리하면_Outbox를_저장하고_ACK한다() {
		
		when(couponIssueLuaService.issue(1L, 100L, "request-1"))
			.thenReturn(
	            new CouponIssueLuaResult(CouponIssueLuaResultStatus.SUCCESS,  1L)
	    );
		
		when(redisTemplate.opsForStream()).thenReturn(streamOperations);
		when(properties.getKey()).thenReturn("coupon:issue:stream");
		when(properties.getGroup()).thenReturn("coupon-issue-group");
		
		MapRecord<String, String, String> message = MapRecord.create(
			"coupon:issue:stream",
			Map.of(
				"requestId", "request-1",
				"couponId", "1",
				"userId", "100"
			)
		);

		when(streamOperations.acknowledge("coupon:issue:stream", "coupon-issue-group", message.getId())).thenReturn(1L);
		
		consumer.onMessage(message);

		verify(couponIssueLuaService).issue(
			1L,   
			100L,
			"request-1"
	    );
		
		verify(couponIssueOutboxService).saveIfAbsent(
			1L,
			100L,
			"request-1",
			1L
		);
		
		verify(streamOperations).acknowledge(
			"coupon:issue:stream",
			"coupon-issue-group",
			message.getId()
		);
	}

	@Test
	void requestId가_issue_형식이_아니어도_품절_판정은_idempotency_확정_없이_ACK한다() {
		// CouponIssueStreamProducer를 직접 호출하는 경로(통합 테스트 등)는 idempotency_key를 안 거치므로
		// requestId가 "issue:{id}" 형식이 아닐 수 있다 — 이 경우도 ACK는 정상적으로 끝나야 한다(펜딩으로 안 남아야 함).
		when(couponIssueLuaService.issue(1L, 100L, "pipeline-test-request"))
			.thenReturn(new CouponIssueLuaResult(CouponIssueLuaResultStatus.SOLD_OUT, 0L));

		when(redisTemplate.opsForStream()).thenReturn(streamOperations);
		when(properties.getKey()).thenReturn("coupon:issue:stream");
		when(properties.getGroup()).thenReturn("coupon-issue-group");

		MapRecord<String, String, String> message = MapRecord.create(
			"coupon:issue:stream",
			Map.of(
				"requestId", "pipeline-test-request",
				"couponId", "1",
				"userId", "100"
			)
		);

		when(streamOperations.acknowledge("coupon:issue:stream", "coupon-issue-group", message.getId()))
			.thenReturn(1L);

		consumer.onMessage(message);

		verify(streamOperations).acknowledge(
			"coupon:issue:stream",
			"coupon-issue-group",
			message.getId()
		);
		verifyNoInteractions(idempotencyKeyService);
		verifyNoInteractions(objectMapper);
	}

	@Test
	void 메시지_필드가_잘못되면_ACK하지_않는다() {
		
		MapRecord<String, String, String> message = MapRecord.create(
			"coupon:issue:stream",
			Map.of(
				"requestId", "request-1",
				"couponId", "invalid",
				"userId", "100"
			)
		);

		consumer.onMessage(message);
		
		verifyNoInteractions(couponIssueLuaService);
        verifyNoInteractions(couponIssueOutboxService);
		verifyNoInteractions(redisTemplate);
	}
	
	@Test
	void 다른_Consumer가_먼저_ACK했다면_멱등_성공으로_처리한다() {
		
		when(couponIssueLuaService.issue(1L, 100L, "request-1"))
			.thenReturn(new CouponIssueLuaResult(CouponIssueLuaResultStatus.SUCCESS, 1L));

		when(redisTemplate.opsForStream()).thenReturn(streamOperations);
		when(properties.getKey()).thenReturn("coupon:issue:stream");
		when(properties.getGroup()).thenReturn("coupon-issue-group");

		MapRecord<String, String, String> message = MapRecord.create("coupon:issue:stream",
			Map.of("requestId", "request-1", "couponId", "1", "userId", "100"));

		when(streamOperations.acknowledge("coupon:issue:stream", "coupon-issue-group", message.getId())).thenReturn(0L);

		when(streamOperations.pending(eq("coupon:issue:stream"), eq("coupon-issue-group"), any(Range.class), eq(1L)))
			.thenReturn(pendingMessages);

		when(pendingMessages.isEmpty()).thenReturn(true);

		consumer.onMessage(message);

		verify(streamOperations).pending(eq("coupon:issue:stream"), eq("coupon-issue-group"), any(Range.class), eq(1L));
	}
}
