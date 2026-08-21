package com.mycom.petcoupon.coupon.issue.consumer;

import java.util.Map;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueMessage;

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

			// TODO: 추후 Redis Lua Script 기반 CouponIssueProcedure 호출
			process(issueMessage);

			// 처리 성공 시에만 ACK
			// 추후 Lua Script 결과가 중복 처리 또는 품절인 경우에도 재처리할 필요가 없으므로 ACK 하도록 분기함 
			redisTemplate.opsForStream().acknowledge(
				properties.getKey(),
				properties.getGroup(),
				message.getId()
			);

			log.info("쿠폰 신청 메시지 처리 및 ACK 완료. messageId={}", message.getId());

		} catch (Exception e) {
			log.error(
				"쿠폰 신청 메시지 처리 실패. messageId={}. ACK하지 않고 Pending 유지",
				message.getId(),
				e
			);
		}
	}

	// 쿠폰 발급 처리 메서드 
	// 현재는 기본 처리 흐름만 구현하며, 추후 Redis Lua Script 기반 CouponIssueProcedure 를 호출함 
	private void process(CouponIssueMessage message) {
		
		// TODO: Redis Lua Script 기반 재고 차감 및 중복 발급 방지 로직 구현
		log.info(
			"쿠폰 발급 처리 임시 수행. requestId={}, couponId={}, userId={}",
			message.requestId(),
			message.couponId(),
			message.userId()
		);
	}
}
