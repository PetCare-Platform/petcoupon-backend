package com.mycom.petcoupon.coupon.issue.consumer;

import java.util.Map;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueMessage;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.idempotency.service.IdempotencyRequestIdCodec;
import com.mycom.petcoupon.messaging.service.CouponIssueOutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

// Redis Stream 메시지를 수신하고 쿠폰 발급 처리를 수행하는 Consumer 
// 처리 성공 시 ACK 하고, 처리 실패 시 ACK 하지 않아서 메시지를 Pending 상태로 남김 
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {
	
	private final StringRedisTemplate redisTemplate;
	private final CouponIssueStreamProperties properties;
	
	private final CouponIssueLuaService couponIssueLuaService;
	private final CouponIssueOutboxService couponIssueOutboxService;
	private final IdempotencyKeyService idempotencyKeyService;
	private final ObjectMapper objectMapper;

	@Override
	public void onMessage(MapRecord<String, String, String> message) {
		
		try {
			Map<String, String> values = message.getValue();

			CouponIssueMessage issueMessage = new CouponIssueMessage(
				values.get("requestId"),
				Long.valueOf(values.get("couponId")),
				Long.valueOf(values.get("userId"))
			);

			log.info(
				"쿠폰 신청 메시지 수신. messageId={}, requestId={}, couponId={}, userId={}",
				message.getId(),
				issueMessage.requestId(),
				issueMessage.couponId(),
				issueMessage.userId()
			);

			CouponIssueLuaResult luaResult = couponIssueLuaService.issue(
				issueMessage.couponId(),
				issueMessage.userId(),
				issueMessage.requestId()
			);
			
			log.info(
				    "[ISSUE] Lua 처리 결과 requestId={} status={} sequenceNo={}",
				    issueMessage.requestId(),
				    luaResult.status(),
				    luaResult.sequenceNo()
			);
			
			switch (luaResult.status()) {
				case SUCCESS, SAME_REQUEST_RETRY -> {
					log.info(
						"[ISSUE] 선점 requestId={} sequenceNo={}", issueMessage.requestId(), luaResult.sequenceNo()
				    );
					
					couponIssueOutboxService.saveIfAbsent(issueMessage.couponId(), issueMessage.userId(), issueMessage.requestId(), luaResult.sequenceNo());

					// Lua 성공은 Redis 재고 선점일 뿐, 아직 Kafka의 최종 DB 발급이 완료된 것은 아니므로 IN_PROGRESS 상태로 유지 
					acknowledge(message);
				}

				case ALREADY_APPLIED -> {
					// SUCCESS와 달리 여기서 끝까지 판정 났음 — 뒤이은 Kafka/DB 확정을 기다릴 필요가 없다.
					saveFailureResult(issueMessage.requestId(), CouponErrorCode.DUPLICATE_USER);
					acknowledge(message);
				}

				case SOLD_OUT -> {
					saveFailureResult(issueMessage.requestId(), CouponErrorCode.SOLD_OUT);
					acknowledge(message);
				}

				case STOCK_NOT_INITIALIZED, SEQUENCE_NOT_FOUND ->
					throw new IllegalStateException("쿠폰 발급 Redis 상태가 정상적이지 않습니다."+ "requestId=" + issueMessage.requestId() + "status=" + luaResult.status());
				
			}
			
			log.info("쿠폰 신청 메시지 처리 및 ACK 완료. messageId={}", message.getId());

		} catch (Exception e) {
			log.error(
				"쿠폰 신청 메시지 처리 실패. messageId={}. ACK하지 않고 Pending 유지",
				message.getId(),
				e
			);
		}
	}
	
	// 처리 성공 시에만 ACK
	// 추후 Lua Script 결과가 중복 처리 또는 품절인 경우에도 재처리할 필요가 없으므로 ACK 하도록 분기함 
	private void acknowledge(MapRecord<String, String, String> message) {
		Long acknowledgedCount = redisTemplate.opsForStream().acknowledge(
	        properties.getKey(),
	        properties.getGroup(),
	        message.getId()
	    );
		
		if (acknowledgedCount == null) {
	        throw new IllegalStateException("Redis Stream ACK결과를 확인할 수 없습니다. messageId = " + message.getId());
	    }
		
		if (acknowledgedCount > 0) {
			return;
		}
		
		/*
		 * 다른 Consumer 또는 Pending Recoverer가 먼저 ACK했을 수 있다.
		 * 실제로 PEL에서 사라졌다면 실패가 아니라 멱등 성공으로 처리한다.
		 */
		PendingMessages pendingMessages = redisTemplate.opsForStream().pending(
			properties.getKey(),
			properties.getGroup(),
			Range.closed(
				message.getId().getValue(),
				message.getId().getValue()
				
			),
			1
		);
		
		if (pendingMessages.isEmpty()) {
			log.info("Redis Stream 메시지가 이미 ACK되었습니다. messageId={}", message.getId());
			return;
		}

		throw new IllegalStateException("Redis Stream ACK에 실패했습니다. messageId=" + message.getId());
	}

	// Lua가 SUCCESS 없이 끝까지 판정 낸 경우(ALREADY_APPLIED/SOLD_OUT) 전용 —
	// 추가로 기다릴 비동기 단계가 없으므로 이 자리에서 바로 idempotency_key를 FAILED로 확정한다.
	//
	// requestId가 "issue:{recordId}" 형식이 아니면(CouponIssueStreamProducer를 직접 호출하는 경로 등)
	// idempotency_key 자체가 없는 요청이므로 조용히 스킵한다 — 여기서 예외를 던지면 ACK가 안 돼서
	// 이미 끝까지 판정 난 메시지가 영원히 Pending으로 남는다.
	private void saveFailureResult(String requestId, CouponErrorCode errorCode) {
		IdempotencyRequestIdCodec.tryDecode(requestId).ifPresentOrElse(
			idempotencyRecordId -> {
				CustomResponse<Void> failure = CustomResponse.onFailure(errorCode);

				idempotencyKeyService.fail(
					idempotencyRecordId,
					errorCode.getStatus().value(),
					objectMapper.writeValueAsString(failure)
				);

				log.info(
					"[ISSUE] 최종 실패 결과 저장. requestId={}, recordId={}, errorCode={}",
					requestId, idempotencyRecordId, errorCode
				);
			},
			() -> log.info(
				"[ISSUE] idempotency_key 확정 스킵(requestId가 issue: 형식이 아님). requestId={}, errorCode={}",
				requestId, errorCode
			)
		);
	}
}
