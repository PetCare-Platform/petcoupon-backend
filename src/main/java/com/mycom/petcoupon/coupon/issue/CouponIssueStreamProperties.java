package com.mycom.petcoupon.coupon.issue;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/*
 * Redis Stream Consumer 설정을 보관하는 Properties 클래스 
 * application.properties 설정값을 주입받아 사용함 
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "coupon.issue.stream")
public class CouponIssueStreamProperties {

	private String key;
	private String group;
	private String consumer;
}
