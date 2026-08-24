package com.mycom.petcoupon.coupon.issue.config;

public class KafkaTopics {

	public static final String COUPON_ISSUE_EVENT = "coupon-issue-events";
	public static final String COUPON_ISSUE_EVENT_DLQ = "coupon-issue-events-dlq";

	private KafkaTopics() {
	}
}
