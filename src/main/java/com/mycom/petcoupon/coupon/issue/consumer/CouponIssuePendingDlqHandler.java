package com.mycom.petcoupon.coupon.issue.consumer;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 최대 처리 횟수를 초과한 Redis Stream Pending 메시지를 DLQ로 이동한다.
// DLQ 저장이 성공한 이후에만 원본 메시지를 ACK하여 메시지 유실을 방지한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssuePendingDlqHandler {
	
	private static final String DLQ_REASON = "MAX_DELIVERY_COUNT_EXCEEDED";
	private static final String REQUEST_SEQUENCE_KEY_FORMAT = "coupon:issue:request-sequence:{%s}";

	private final StringRedisTemplate redisTemplate;
	private final CouponIssueStreamProperties properties;

	public void moveToDlq(
		MapRecord<String, String, String> message,
		long deliveryCount
	) {
		CouponIssueStreamProperties.PendingRecovery recovery = properties.getPendingRecovery();

		String sourceStreamKey = properties.getKey();
		String group = properties.getGroup();
		String dlqKey = recovery.getDlqKey();

		Map<String, String> dlqValues = new HashMap<>(message.getValue());

		dlqValues.put("originalMessageId", message.getId().getValue());
		dlqValues.put("deliveryCount", String.valueOf(deliveryCount));
		dlqValues.put("failedAt", Instant.now().toString());
		dlqValues.put("reason", DLQ_REASON);

		addSequenceNoIfPresent(dlqValues);

		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();

		/*
		 * ACK보다 DLQ 저장을 먼저 수행한다.
		 * DLQ 저장이 실패하면 예외가 발생하고 아래 ACK는 실행되지 않으므로
		 * 원본 메시지는 Pending 상태로 유지된다.
		 */
		RecordId dlqMessageId = streamOperations.add(MapRecord.create(dlqKey, dlqValues));

		if (dlqMessageId == null) {
			throw new IllegalStateException("Redis Stream DLQ 저장에 실패했습니다. originalMessageId=" + message.getId());
		}

		Long acknowledgedCount = streamOperations.acknowledge(sourceStreamKey, group, message.getId());

		if (acknowledgedCount == null || acknowledgedCount == 0) {
			/*
			 * DLQ 저장 후 ACK만 실패하면 다음 회수에서 DLQ 중복 적재가
			 * 발생할 수 있다. DLQ 재처리 시 originalMessageId를 기준으로
			 * 멱등 처리해야 한다.
			 */
			throw new IllegalStateException(
				"DLQ 저장 후 원본 메시지 ACK에 실패했습니다. "
				+ "originalMessageId=" + message.getId()
				+ ", dlqMessageId=" + dlqMessageId
			);
		}

		log.warn(
			"Redis Stream Pending 메시지를 DLQ로 이동했습니다. " + "originalMessageId={}, dlqMessageId={}, " + "deliveryCount={}, reason={}",
			message.getId(),
			dlqMessageId,
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
