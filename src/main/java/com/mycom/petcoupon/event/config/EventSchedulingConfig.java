package com.mycom.petcoupon.event.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import com.mycom.petcoupon.event.service.EventStatusSchedulerService;

import lombok.RequiredArgsConstructor;

/**
 * 이벤트 상태 전환 스케줄러 전용 TaskScheduler.
 * 앱 전체 @Scheduled가 공유하는 RedisStreamSchedulerConfig의 풀 사이즈 1짜리 스레드와 분리해서,
 * Redis Stream 재시도(더 중요한 쿠폰 발급 경로)와 이벤트 스케줄러가 서로 지연시키지 않게 한다.
 */
@Configuration
@RequiredArgsConstructor
public class EventSchedulingConfig implements SchedulingConfigurer {

	private final EventStatusSchedulerService eventStatusSchedulerService;

	@Value("${event.status.scheduler.cron:0 * * * * *}")
	private String cron;

	@Override
	public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("event-status-scheduler-");
		scheduler.initialize();

		taskRegistrar.setTaskScheduler(scheduler);
		taskRegistrar.addTriggerTask(
				eventStatusSchedulerService::syncEventStatuses,
				new CronTrigger(cron)
		);
	}
}
