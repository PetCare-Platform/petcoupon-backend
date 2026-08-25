package com.mycom.petcoupon.event.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.event.service.EventStatusSchedulerService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @Scheduled와 SchedulingConfigurer는 앱 전체가 공유하는 단일 ScheduledTaskRegistrar를 쓴다.
 * SchedulingConfigurer#configureTasks에서 setTaskScheduler를 호출하면, 그 레지스트라에 등록된
 * 다른 @Scheduled 메서드(예: CouponExpireBatchServiceImpl)까지 전부 그 스케줄러로 조용히 옮겨간다.
 * 그래서 SchedulingConfigurer를 쓰지 않고, 이 컴포넌트가 자기 전용 TaskScheduler를 직접 만들어서
 * schedule()을 호출하는 방식으로 공유 레지스트라와 완전히 분리시킨다.
 */
@Slf4j
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

		scheduler.schedule(this::runSafely, new CronTrigger(cron));
	}

	// 예외를 서비스 메서드 "바깥"에서 잡는다. @Transactional 프록시는 커넥션 획득 -> 메서드 본문 ->
	// 커밋 순으로 도는데, 본문 안에서 잡으면 커넥션 획득 실패(부하 시 커넥션 풀 고갈 등)나 커밋
	// 실패는 본문 바깥에서 터지므로 잡히지 않는다. 또 본문 안에서 예외를 삼키면 트랜잭션이
	// rollback-only로 마킹돼 커밋 시점에 UnexpectedRollbackException이 새로 터져서, 로그에 진짜
	// 원인 대신 롤백 예외가 남는다.
	private void runSafely() {
		try {
			eventStatusSchedulerService.syncEventStatuses();
		} catch (Throwable t) {
			log.error("이벤트 상태 전환 스케줄러 실행 중 오류가 발생했습니다. 다음 주기에 재시도합니다.", t);
		}
	}

	@PreDestroy
	public void stop() {
		if (scheduler != null) {
			scheduler.shutdown();
		}
	}
}
