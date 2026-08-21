package com.mycom.petcoupon.coupon.issue.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;


@Configuration
public class RedisStreamSchedulerConfig {

    @Bean
    public TaskScheduler redisStreamRecoveryTaskScheduler() {
    	
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("redis-stream-recovery-");
        scheduler.initialize();
        return scheduler;
    }
}