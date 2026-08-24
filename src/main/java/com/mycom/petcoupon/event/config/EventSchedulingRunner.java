package com.mycom.petcoupon.event.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.event.service.EventStatusSchedulerService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/**
 * @Scheduled와 SchedulingConfigurer는 앱 전체가 공유하는 단일 ScheduledTaskRegistrar를 쓴다.
 * SchedulingConfigurer#configureTasks에서 setTaskScheduler를 호출하면, 그 레지스트라에 등록된
 * 다른 @Scheduled 메서드(예: CouponExpireBatchServiceImpl)까지 전부 그 스케줄러로 조용히 옮겨간다.
 * 그래서 SchedulingConfigurer를 쓰지 않고, 이 컴포넌트가 자기 전용 TaskScheduler를 직접 만들어서
 * schedule()을 호출하는 방식으로 공유 레지스트라와 완전히 분리시킨다.
 */
@Component
@RequiredArgsConstructor
public class EventSchedulingRunner {

	private final EventStatusSchedulerService eventStatusSchedulerService;

	@Value("${event.status.scheduler.cron:0 * * * * *}")
	private String cron;

	private ThreadPoolTaskScheduler scheduler;

	@PostConstruct
	public void start() {
		scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("event-status-scheduler-");
		scheduler.initialize();

		scheduler.schedule(eventStatusSchedulerService::syncEventStatuses, new CronTrigger(cron));
	}

	@PreDestroy
	public void stop() {
		if (scheduler != null) {
			scheduler.shutdown();
		}
	}
}
