package com.mycom.petcoupon.coupon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// CouponExpireBatchServiceImpl 전용 스케줄러.
// TaskScheduler 빈이 여러 개일 때 @Scheduled에 scheduler를 명시하지 않으면
// Spring이 어떤 빈을 쓸지 애매해져서, redisStreamRecoveryTaskScheduler처럼
// 다른 용도의 스레드 풀을 그대로 가져다 쓰는 일이 생길 수 있다.
@Configuration
public class CouponBatchSchedulerConfig {

	@Bean
	public TaskScheduler couponExpireBatchTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("coupon-expire-batch-");
		scheduler.initialize();
		return scheduler;
	}
}
