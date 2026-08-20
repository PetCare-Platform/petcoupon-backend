package com.mycom.petcoupon.coupon.issue.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueStreamConsumer;

import lombok.RequiredArgsConstructor;

// Redis Stream Consumer 설정 클래스 
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(CouponIssueStreamProperties.class)
public class CouponIssueStreamConfig {

	private final CouponIssueStreamProperties properties;
	
	@Bean
	public StreamMessageListenerContainer<String, MapRecord<String, String, String>> couponIssueStreamContainer(
		RedisConnectionFactory connectionFactory,
		CouponIssueStreamConsumer consumer
	) {
		ensureConsumerGroup(connectionFactory);

		// Redis Stream 을 계속 감시하는 Listener Container 설정 
		StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
			StreamMessageListenerContainer.StreamMessageListenerContainerOptions
				.builder()
				.batchSize(10)
				.build();

		// Container 생성 
		StreamMessageListenerContainer<String, MapRecord<String, String, String>> container = StreamMessageListenerContainer.create(connectionFactory, options);

		container.receive(
			Consumer.from(properties.getGroup(), properties.getConsumer()),
			StreamOffset.create(
				properties.getKey(),
				ReadOffset.lastConsumed()
			),
			consumer
		);

		container.start();

		return container;
	}
	
	// Consumer Group이 존재하지 않으면 생성함 
	private void ensureConsumerGroup(RedisConnectionFactory connectionFactory) {
		
		StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);

		try {
			redisTemplate.opsForStream().createGroup(
				properties.getKey(),
				ReadOffset.from("0-0"),		// Stream의 가장 처음 메시지부터 읽도록 설정 
				properties.getGroup()
			);
		} catch (Exception e) {
			// 이미 Group이 존재하는 경우 혹은 null 은 무시
			if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
				throw e;
			}
		}
	}
}
