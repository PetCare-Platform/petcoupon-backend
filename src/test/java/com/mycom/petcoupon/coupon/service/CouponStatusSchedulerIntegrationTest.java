package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * transitionCouponStatuses()를 직접 호출하는 CouponStatusSchedulerServiceImplTest와 달리,
 * 여기서는 아무것도 수동으로 부르지 않는다 — CouponStatusSchedulerRegistrar가 등록한
 * 단일 스레드 ScheduledExecutorService가 실제로 자동 실행되는지를 검증한다.
 * 주기를 1초로 낮추고, 실행되는 즉시 통과하도록 고정 sleep 대신 Awaitility로 폴링한다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@SpringBootTest(properties = {
		"coupon.status.interval-seconds=1",
		"coupon.issue.stream.enabled=false",
		// 이 테스트는 쿠폰 스케줄러만 검증 대상이므로 무관한 이벤트 스케줄러만 끔
		"event.status.scheduler.enabled=false"
})
class CouponStatusSchedulerIntegrationTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private AppUser admin;
	private Event event;
	private Coupon coupon;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> setUpData());
	}

	private void setUpData() {
		admin = AppUser.builder()
				.name("스케줄러 통합 테스트 관리자")
				.email("scheduler-integration-admin@test.com")
				.phone("010-3333-3333")
				.role(UserRole.ROLE_ADMIN)
				.build();
		entityManager.persist(admin);

		event = Event.builder()
				.createdBy(admin)
				.name("스케줄러 통합 테스트 이벤트")
				.description("scheduler integration")
				.openAt(LocalDateTime.now().minusDays(1))
				.closeAt(LocalDateTime.now().plusDays(1))
				.build();
		entityManager.persist(event);

		// 이미 발급 시작 시각이 지난 READY 쿠폰 -> 등록된 스케줄러가 실제로 돌면 자동으로 ACTIVE가 돼야 함
		coupon = Coupon.builder()
				.event(event)
				.name("스케줄러 통합 테스트 쿠폰")
				.discountType(DiscountType.FIXED_AMOUNT)
				.discountValue(1_000)
				.minOrderAmount(0)
				.issueStartAt(LocalDateTime.now().minusMinutes(1))
				.issueEndAt(LocalDateTime.now().plusDays(1))
				.validDays(7)
				.build();
		entityManager.persist(coupon);

		entityManager.flush();
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> tearDownData());
	}

	private void tearDownData() {
		entityManager.createNativeQuery("DELETE FROM coupon WHERE coupon_id = :couponId")
				.setParameter("couponId", coupon.getCouponId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM event_status_history WHERE event_id = :eventId")
				.setParameter("eventId", event.getEventId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM event WHERE event_id = :eventId")
				.setParameter("eventId", event.getEventId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id = :userId")
				.setParameter("userId", admin.getUserId())
				.executeUpdate();
	}

	@Test
	void 등록된_스케줄러가_자동으로_READY_쿠폰을_ACTIVE로_전이시킨다() {
		await()
				.atMost(Duration.ofSeconds(5))
				.pollInterval(Duration.ofMillis(200))
				.untilAsserted(() ->
						assertThat(couponRepository.findById(coupon.getCouponId()).orElseThrow().getStatus())
								.isEqualTo(CouponStatus.ACTIVE)
				);
	}
}
