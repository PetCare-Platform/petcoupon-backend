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
}
