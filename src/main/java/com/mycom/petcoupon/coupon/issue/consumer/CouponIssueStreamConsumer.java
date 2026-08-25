package com.mycom.petcoupon.coupon.issue.consumer;

import java.util.Map;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueMessage;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.messaging.service.CouponIssueOutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
					// TODO: idempotency_key에 ALREADY_APPLIED 결과 저장 후 ACK
					acknowledge(message);
				}
				
				case SOLD_OUT -> {
					// TODO: idempotency_key에 SOLD_OUT 결과 저장 후 ACK 
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
		
		if (acknowledgedCount == null || acknowledgedCount == 0) {
	        throw new IllegalStateException("Redis Stream ACK에 실패했습니다. messageId = " + message.getId());
	    }
	}
}
