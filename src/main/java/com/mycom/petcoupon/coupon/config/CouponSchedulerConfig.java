package com.mycom.petcoupon.coupon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * cron 기반 @Scheduled 배치(CouponExpireBatchService, CouponStatusSchedulerService 등) 전용 TaskScheduler.
 * Redis Stream 오류 복구용 TaskScheduler(RedisStreamSchedulerConfig.redisStreamRecoveryTaskScheduler)와는
 * 완전히 분리된 풀이다 — 같은 풀을 공유하면, 1분마다 도는 쿠폰 상태 전이가 도는 동안 Redis Stream
 * 오류 복구 예약이 밀릴 수 있다. 이 빈 이름을 "taskScheduler"로 둬서 @Scheduled 전역 등록기가
 * (다른 후보가 없는 한) 자동으로 이 풀을 집어가게 한다.
 */
@Configuration
public class CouponSchedulerConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("coupon-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
