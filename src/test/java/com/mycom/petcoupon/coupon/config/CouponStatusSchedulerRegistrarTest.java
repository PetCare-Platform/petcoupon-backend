package com.mycom.petcoupon.coupon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.service.CouponStatusSchedulerService;

/**
 * scheduleAtFixedRate는 태스크가 예외를 한 번이라도 던지면 이후 실행을 영구히 멈춘다(JDK 명세).
 * 예외를 서비스 메서드 안에서 잡으면 @Transactional 경계(커넥션 획득 실패·커밋 실패)에서 난
 * 예외는 잡히지 않으므로, Registrar가 Runnable 자체를 감싸야 한다.
 *
 * 이 테스트는 그 감싸기가 실제로 동작하는지를 "매번 예외를 던지는 서비스"로 검증한다 —
 * 감싸기가 없으면 호출 횟수가 1에서 멈춘다.
 */
class CouponStatusSchedulerRegistrarTest {

	private CouponStatusSchedulerRegistrar registrar;

	@AfterEach
	void tearDown() {
		if (registrar != null) {
			registrar.stop();
		}
	}

	@Test
	void 서비스가_매번_예외를_던져도_다음_주기에_계속_재시도한다() {
		AtomicInteger callCount = new AtomicInteger();

		// 트랜잭션 경계에서 터지는 예외(커넥션 풀 고갈 등)를 흉내낸다 — 스케줄러 입장에선
		// 서비스 호출이 그냥 예외를 던진 것과 동일하다.
		CouponStatusSchedulerService alwaysFailing = () -> {
			callCount.incrementAndGet();
			throw new IllegalStateException("트랜잭션 경계 예외 시뮬레이션");
		};

		registrar = new CouponStatusSchedulerRegistrar(alwaysFailing, 1L);
		registrar.start();

		// 첫 예외 이후로도 주기가 살아있어야 하므로 호출이 계속 누적된다.
		await()
				.atMost(Duration.ofSeconds(10))
				.pollInterval(Duration.ofMillis(200))
				.untilAsserted(() -> assertThat(callCount.get()).isGreaterThanOrEqualTo(3));
	}
}
