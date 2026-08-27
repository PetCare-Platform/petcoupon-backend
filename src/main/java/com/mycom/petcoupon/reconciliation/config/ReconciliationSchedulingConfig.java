package com.mycom.petcoupon.reconciliation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// OutboxSchedulingConfig/CouponBatchSchedulerConfig와 같은 이유로 전용 TaskScheduler를 둔다 —
// 정합성 검증은 쿠폰마다 Spring Batch Job을 끝까지 블로킹으로 돌리는(coupon 수만큼 X초씩) 무거운
// 작업이라, 앱 기본 스케줄러 풀을 같이 쓰면 Outbox/이벤트 상태 전이 등 다른 짧은 주기 스케줄러가
// 지연될 수 있다.
@Configuration
public class ReconciliationSchedulingConfig {

    @Bean
    public TaskScheduler reconciliationSchedulerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("coupon-reconciliation-scheduler-");
        scheduler.initialize();

        return scheduler;
    }
}
