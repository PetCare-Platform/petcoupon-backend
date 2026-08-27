package com.mycom.petcoupon.coupon.issue.config;

import java.time.Duration;

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

	private PendingRecovery pendingRecovery = new PendingRecovery();

	@Getter
    @Setter
    public static class PendingRecovery {

        // Pending 회수 스케줄러 활성화 여부
        private boolean enabled = true;

        // 정상 처리 중인 메시지를 회수하지 않기 위한 최소 대기 시간
        private Duration minIdleTime = Duration.ofMinutes(1);

        // Pending 회수 작업 실행 간격
        private Duration fixedDelay = Duration.ofSeconds(5);

        // 한 번에 회수할 Pending 메시지 수
        private int batchSize = 100;

        /*
    	 * 최초 Consumer 처리를 포함한 최대 처리 횟수.
    	 * 이 횟수만큼 처리했는데도 ACK되지 않으면 DLQ로 이동한다.
    	 */
    	private int maxDeliveryCount = 3;

    	// 최종 처리 실패 메시지를 저장할 Redis Stream
    	private String dlqKey = "coupon:issue:stream:dlq";
    }
}
