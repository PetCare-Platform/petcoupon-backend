package com.mycom.petcoupon.coupon.issue.consumer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumer 처리 실패로 Pending 상태에 남은 Redis Stream 메시지를
 * 현재 Consumer로 회수하고 기존 Consumer 처리 흐름에 다시 전달한다.
 *
 * 최대 처리 횟수에 도달한 메시지는 기존 Consumer로 다시 전달하지 않고
 * Redis Stream DLQ로 이동한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssuePendingMessageRecoverer {

	private final StringRedisTemplate redisTemplate;
	private final CouponIssueStreamProperties properties;
	private final CouponIssueStreamConsumer streamConsumer;
	private final CouponIssuePendingDlqHandler dlqHandler;

	/**
	 * 최소 Idle Time을 초과한 Pending 메시지를 한 배치만큼 회수한다.
	 * @return 재처리를 요청했거나 DLQ 이동을 완료한 메시지 수
	 */
	public int recoverPendingMessages() {
		String streamKey = properties.getKey();
		String group = properties.getGroup();
		String recoveryConsumer = properties.getConsumer();

		CouponIssueStreamProperties.PendingRecovery recovery = properties.getPendingRecovery();

		if (!Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
			return 0;
		}

		validate(recovery, streamKey);

		StreamOperations<String, String, String> streamOperations = redisTemplate.opsForStream();

		Duration minIdleTime = recovery.getMinIdleTime();

		PendingMessages pendingMessages = streamOperations.pending(streamKey, group, Range.unbounded(), recovery.getBatchSize(), minIdleTime);

		if (pendingMessages.isEmpty()) {
			return 0;
		}

		/*
		 * XCLAIM 이후에도 각 메시지의 기존 deliveryCount를 확인할 수 있도록
		 * 메시지 ID를 기준으로 Pending 정보를 보관한다.
		 */
		Map<String, PendingMessage> pendingById = pendingMessages.stream()
			.collect(
				Collectors.toMap(PendingMessage::getIdAsString, Function.identity())
		);

		RecordId[] pendingIds = pendingMessages.stream().map(PendingMessage::getId).toArray(RecordId[]::new);
		
		/*
		 * XPENDING과 XCLAIM 사이에 다른 서버가 먼저 가져갈 수 있다. XCLAIM이 minIdleTime을 다시 검사하므로 실제
		 * 소유권을 가져온 메시지만 claimedMessages에 반환된다.
		 */
		List<MapRecord<String, String, String>> claimedMessages = streamOperations.claim(streamKey, group, recoveryConsumer, minIdleTime, pendingIds);

		int handledCount = 0;
		
		for (MapRecord<String, String, String> message : claimedMessages) {
			PendingMessage pendingMessage = pendingById.get(message.getId().getValue());
			
			if (pendingMessage == null) {
				log.error("회수한 메시지의 Pending 정보를 찾지 못했습니다. " + "messageId={}", message.getId());
				continue;
			}
			
			long deliveryCount = pendingMessage.getTotalDeliveryCount();
			
			if (deliveryCount >= recovery.getMaxDeliveryCount()) {

				try {
					dlqHandler.moveToDlq(message, deliveryCount);

					handledCount++;

				} catch (Exception e) {
					/*
					 * DLQ 저장 또는 원본 ACK가 실패하면 메시지는
					 * Pending 상태로 유지하고 다음 주기에 다시 시도한다.
					 */
					log.error("Redis Stream Pending 메시지 DLQ 이동 실패. " + "messageId={}, deliveryCount={}",
						message.getId(),
						deliveryCount,
						e
					);
				}

				continue;
			}
			
			log.debug("Redis Stream Pending 메시지 회수. " + "messageId={}, consumer={}, " + "deliveryCount={}/{}",
				message.getId(),
				recoveryConsumer,
				deliveryCount,
				recovery.getMaxDeliveryCount()
			);
			

			/*
			 * 발급 로직을 복제하지 않고 기존 Lua → Outbox → ACK 처리를 재사용한다. 처리에 실패하면
			 * CouponIssueStreamConsumer가 ACK하지 않으므로 메시지는 다시 Pending 상태로 남고 다음 주기에 재시도된다.
			 */
			streamConsumer.onMessage(message);
			handledCount++;
		}

		return handledCount;
	}

	private void validate(CouponIssueStreamProperties.PendingRecovery recovery, String streamKey) {
		
		if (recovery.getBatchSize() <= 0) {
			throw new IllegalStateException("Pending recovery batchSize는 1 이상이어야 합니다.");
		}

		if (recovery.getMinIdleTime() == null || recovery.getMinIdleTime().isNegative()) {
			throw new IllegalStateException("Pending recovery minIdleTime은 0 이상이어야 합니다.");
		}
		
		if (recovery.getMaxDeliveryCount() <= 0) {
			throw new IllegalStateException(
					"Pending recovery maxDeliveryCount는 1 이상이어야 합니다."
			);
		}

		if (recovery.getDlqKey() == null || recovery.getDlqKey().isBlank()) {
			throw new IllegalStateException("Pending recovery dlqKey는 비어 있을 수 없습니다.");
		}

		if (streamKey.equals(recovery.getDlqKey())) {
			throw new IllegalStateException("원본 Stream key와 DLQ key는 달라야 합니다.");
		}
	}
}
