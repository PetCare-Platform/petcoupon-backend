package com.mycom.petcoupon.coupon.issue.consumer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 최대 처리 횟수를 초과한 Redis Stream Pending 메시지를 DLQ로 이동한다.
// DLQ 저장과 원본 ACK를 Lua로 처리하여 두 명령 사이에 다른 Consumer의 ACK가 끼어드는 경쟁 조건을 방지한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssuePendingDlqHandler {
	
	private static final String DLQ_REASON = "MAX_DELIVERY_COUNT_EXCEEDED";
	private static final String ALREADY_ACKNOWLEDGED = "ALREADY_ACKNOWLEDGED";
	private static final String REQUEST_SEQUENCE_KEY_FORMAT = "coupon:issue:request-sequence:{%s}";

	private final StringRedisTemplate redisTemplate;
	private final CouponIssueStreamProperties properties;
	
	private final DefaultRedisScript<String> couponIssuePendingToDlqLuaScript;

	public void moveToDlq(
		MapRecord<String, String, String> message,
		long deliveryCount
	) {
		CouponIssueStreamProperties.PendingRecovery recovery = properties.getPendingRecovery();

		Map<String, String> dlqValues = new HashMap<>(message.getValue());
		
		dlqValues.put("originalMessageId", message.getId().getValue());
		dlqValues.put("deliveryCount", String.valueOf(deliveryCount));
		dlqValues.put("failedAt", Instant.now().toString());
		dlqValues.put("reason", DLQ_REASON);

		addSequenceNoIfPresent(dlqValues);
		
		/*
		 * Lua Script 인자:
		 * ARGV[1] = Consumer Group
		 * ARGV[2] = 원본 Message ID
		 * ARGV[3] 이후 = DLQ에 저장할 field/value 쌍
		 */
		List<String> arguments = new ArrayList<>();
		
		arguments.add(properties.getGroup());
		arguments.add(message.getId().getValue());

		dlqValues.forEach((field, value) -> {
			arguments.add(field);
			arguments.add(value);
		});

		/*
		 * Lua에서 다음 작업을 하나로 처리한다.
		 *
		 * 1. DLQ Stream에 메시지 저장
		 * 2. 원본 메시지 ACK
		 * 3. 원본이 이미 ACK된 경우 방금 생성한 DLQ 메시지 삭제
		 *
		 * DLQ 저장 자체가 실패하면 XACK은 실행되지 않으므로
		 * 원본 메시지는 Pending 상태로 유지된다.
		 */
		String result = redisTemplate.execute(
			couponIssuePendingToDlqLuaScript,
			List.of(
				properties.getKey(),
				recovery.getDlqKey()
			),
			arguments.toArray()
		);

		if (result == null) {
			throw new IllegalStateException("Redis Stream DLQ 이동 결과를 확인할 수 없습니다. " + "originalMessageId=" + message.getId());
		}
			
		// 원본 Consumer가 먼저 ACK한 경우 Lua에서 방금 생성한 DLQ 메시지를 삭제했으므로 정상적인 경쟁 결과로 처리한다.
		if (ALREADY_ACKNOWLEDGED.equals(result)) {
			log.info("원본 메시지가 이미 ACK되어 DLQ 기록을 남기지 않습니다. " + "originalMessageId={}", message.getId());
			return;
		}

		log.warn("Redis Stream Pending 메시지를 DLQ로 이동했습니다. " + "originalMessageId={}, dlqMessageId={}, " + "deliveryCount={}, reason={}",
			message.getId(),
			result,
			deliveryCount,
			DLQ_REASON
		);
	}
		
	/**
	 * Lua 성공 후 Outbox 저장에서 반복 실패한 메시지라면
	 * Redis request-sequence Hash에 발급 순번이 존재한다.
	 * 추후 관리자 재처리 또는 재고 복구 판단에 사용할 수 있도록 DLQ에 함께 저장한다.
	 */
	private void addSequenceNoIfPresent(Map<String, String> dlqValues) {
		
		String couponId = dlqValues.get("couponId");
		String requestId = dlqValues.get("requestId");

		if (couponId == null || couponId.isBlank() || requestId == null || requestId.isBlank()) {
			return;
		}

		String requestSequenceKey = String.format(REQUEST_SEQUENCE_KEY_FORMAT, couponId);

		Object sequenceNo = redisTemplate.opsForHash().get(requestSequenceKey, requestId);

		if (sequenceNo != null) {
			dlqValues.put("sequenceNo", sequenceNo.toString());
		}
	}
}
