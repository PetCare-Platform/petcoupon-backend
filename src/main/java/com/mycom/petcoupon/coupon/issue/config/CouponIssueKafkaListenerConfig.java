package com.mycom.petcoupon.coupon.issue.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class CouponIssueKafkaListenerConfig {

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> couponIssueKafkaListenerContainerFactory(
			ConsumerFactory<String, Object> consumerFactory,
			ConsumerRecordRecoverer couponIssueEventRecoverer) {
		ConcurrentKafkaListenerContainerFactory<String, Object> factory =
				new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);
		factory.setCommonErrorHandler(
				new DefaultErrorHandler(couponIssueEventRecoverer, new FixedBackOff(1000L, 2L)));
		factory.setConcurrency(3);
		return factory;
	}

	// DLQ Consumer 전용 팩토리(#112) — 원래 containerFactory를 지정 안 하고 Spring Boot 기본
	// 팩토리로 fallback했었는데, 실제 Kafka로 검증해보니 그 기본 팩토리가 이 ConsumerFactory(JSON
	// 역직렬화 설정 포함)를 안 쓰고 있어서 MessageConversionException이 나는 게 확인됨
	// (payload가 CouponIssueEvent가 아니라 원본 JSON 문자열로 들어옴). 재시도/Recoverer는 필요 없어서
	// (로그만 남기는 정책) couponIssueKafkaListenerContainerFactory처럼 에러 핸들러는 안 붙임.
	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> couponIssueDlqKafkaListenerContainerFactory(
			ConsumerFactory<String, Object> consumerFactory) {
		ConcurrentKafkaListenerContainerFactory<String, Object> factory =
				new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);
		return factory;
	}
}
