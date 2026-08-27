package com.mycom.petcoupon.coupon.issue.config;

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

	// Stream pending을 "이미 배달돼 곧 ACK될 것"과 "죽은 Consumer가 방치한 것"으로 가르는
	// 기준(idle 시간, ms). 정상 처리(DB 저장 1건)는 밀리초 단위로 끝나므로, GC 정지·일시적
	// DB 지연까지 감안해 넉넉히 잡아도 죽은 Consumer 판정과는 자릿수가 다르다.
	private long pendingIdleThresholdMs = 30_000;
}
