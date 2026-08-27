package com.mycom.petcoupon.coupon.issue.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;


@Configuration
public class RedisStreamSchedulerConfig {

	/**
     * Stream Listener 읽기 오류 복구 전용 스케줄러.
     */
    @Bean
    public TaskScheduler redisStreamRecoveryTaskScheduler() {
    	
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("redis-stream-recovery-");
        scheduler.initialize();
        return scheduler;
    }
    
    /**
     * Pending 메시지 회수 전용 스케줄러.
     * 기존 오류 복구 스케줄러와 분리하여 Pending 재처리가 길어지더라도 Listener Container 재시작 작업을 막지 않도록 한다.
     */
    @Bean
    public TaskScheduler redisStreamPendingRecoveryTaskScheduler() {

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("redis-stream-pending-");
        scheduler.initialize();

        return scheduler;
    }
}