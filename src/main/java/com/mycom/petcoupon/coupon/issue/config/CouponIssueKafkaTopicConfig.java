package com.mycom.petcoupon.coupon.issue.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class CouponIssueKafkaTopicConfig {

	@Bean
	public NewTopic couponIssueEventTopic() {
		return TopicBuilder.name(KafkaTopics.COUPON_ISSUE_EVENT)
			.partitions(3)
			.replicas(1)
			.build();
	}

	@Bean
	public NewTopic couponIssueEventDlqTopic() {
		return TopicBuilder.name(KafkaTopics.COUPON_ISSUE_EVENT_DLQ)
			.partitions(3)
			.replicas(1)
			.build();
	}
}
