package com.mycom.petcoupon.messaging.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// @EnableScheduling은 OutboxSchedulingConfig에서 이미 켜져 있다. 전용 스레드 풀만 분리한다 —
// 다른 스케줄러와 실행을 서로 지연시키지 않기 위함.
@Configuration
public class ReprocessRecoverySchedulingConfig {

	@Bean
	public TaskScheduler reprocessRecoveryTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("coupon-reprocess-recovery-");
		scheduler.initialize();

		return scheduler;
	}
}
