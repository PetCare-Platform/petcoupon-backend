package com.mycom.petcoupon.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.mycom.petcoupon.coupon.config.CouponStatusSchedulerRegistrar;
import com.mycom.petcoupon.coupon.issue.config.CouponIssuePendingRecoveryScheduler;
import com.mycom.petcoupon.event.config.EventSchedulingRunner;

/**
 * 스케줄러 on/off 스위치가 실제로 빈 등록을 막는지 검증한다.
 *
 * 이 테스트가 없으면 @ConditionalOnProperty가 지워지거나 프로퍼티 이름이
 * 바뀌어도 아무도 모른 채 넘어간다. 그러면 다른 테스트가 간헐적으로 깨지는
 * 형태로만 드러나는데, 그건 원인을 찾기가 매우 어렵다.
 *
 * 실행 전 MySQL과 Redis가 떠 있어야 한다: docker compose up -d mysql redis
 */
class SchedulerToggleTest {

	@Nested
	@SpringBootTest(properties = {
			"event.status.scheduler.enabled=false",
			"coupon.status.enabled=false",
			"coupon.issue.stream.enabled=false",
			"coupon.issue.outbox.enabled=false"
	})
	class WhenDisabled {

		@Autowired
		private ApplicationContext context;

		@Test
		void eventStatusSchedulerBeanIsNotRegistered() {
			assertThat(context.getBeanNamesForType(EventSchedulingRunner.class)).isEmpty();
		}

		@Test
		void couponStatusSchedulerBeanIsNotRegistered() {
			assertThat(context.getBeanNamesForType(CouponStatusSchedulerRegistrar.class)).isEmpty();
		}
	}

	// 설정을 주지 않았을 때 켜져 있어야 한다(matchIfMissing = true).
	// 여기가 깨지면 운영에서 상태 전이가 멈춘다.
	@Nested
	@SpringBootTest(properties = {
			"coupon.issue.stream.enabled=false",
			"coupon.issue.outbox.enabled=false"
	})
	class WhenNotConfigured {

		@Autowired
		private ApplicationContext context;

		@Test
		void eventStatusSchedulerIsEnabledByDefault() {
			assertThat(context.getBeanNamesForType(EventSchedulingRunner.class)).hasSize(1);
		}

		@Test
		void couponStatusSchedulerIsEnabledByDefault() {
			assertThat(context.getBeanNamesForType(CouponStatusSchedulerRegistrar.class)).hasSize(1);
		}
	}
	
	@Nested
	@SpringBootTest(properties = {
		"event.status.scheduler.enabled=false",
		"coupon.status.enabled=false",
		"coupon.issue.stream.enabled=true",
		"coupon.issue.stream.pending-recovery.enabled=false",
		"coupon.issue.stream.key=coupon:issue:stream:scheduler-toggle-disabled",
		"coupon.issue.stream.group=scheduler-toggle-disabled-group",
		"coupon.issue.stream.consumer=scheduler-toggle-disabled-consumer",
		"coupon.issue.outbox.enabled=false",
		"spring.kafka.listener.auto-startup=false"
	})
	class WhenPendingRecoveryDisabled {

		@Autowired
		private ApplicationContext context;

		@Test
		void pendingRecoverySchedulerBeanIsNotRegistered() {
			assertThat(context.getBeanNamesForType(CouponIssuePendingRecoveryScheduler.class)).isEmpty();
		}
	}

	@Nested
	@SpringBootTest(properties = {
		"event.status.scheduler.enabled=false",
		"coupon.status.enabled=false",
		"coupon.issue.stream.enabled=true",
		"coupon.issue.stream.pending-recovery.enabled=true",
		"coupon.issue.stream.pending-recovery.fixed-delay=PT1H",
		"coupon.issue.stream.key=coupon:issue:stream:scheduler-toggle-enabled",
		"coupon.issue.stream.group=scheduler-toggle-enabled-group",
		"coupon.issue.stream.consumer=scheduler-toggle-enabled-consumer",
		"coupon.issue.outbox.enabled=false",
		"spring.kafka.listener.auto-startup=false"
	})
	class WhenPendingRecoveryEnabled {

		@Autowired
		private ApplicationContext context;

		@Test
		void pendingRecoverySchedulerBeanIsRegistered() {
			assertThat(context.getBeanNamesForType(CouponIssuePendingRecoveryScheduler.class)).hasSize(1);
		}
	}
}
