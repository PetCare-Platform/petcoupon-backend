package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

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
 * 조건부 원자적 UPDATE(activateCoupons/endCoupons)가 실제 DB에서 의도한 상태만 골라
 * 전이시키는지 검증. CouponExpireBatchServiceImplTest와 동일하게 @DataJpaTest + 실제
 * MySQL로 검증한다(벌크 쿼리는 Mock으로 오타를 못 잡음).
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CouponStatusSchedulerServiceImpl.class)
class CouponStatusSchedulerServiceImplTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CouponStatusSchedulerServiceImpl couponStatusSchedulerService;

	@Autowired
	private CouponRepository couponRepository;

	private Event event;
	private int sequenceCounter = 0;

	@BeforeEach
	void setUp() {
		AppUser admin = AppUser.builder()
				.name("관리자")
				.email("status-scheduler-admin@test.com")
				.phone("010-1234-5678")
				.role(UserRole.ROLE_ADMIN)
				.build();
		entityManager.persist(admin);

		event = Event.builder()
				.createdBy(admin)
				.name("상태 스케줄러 테스트 이벤트")
				.description("status scheduler")
				.openAt(LocalDateTime.of(2026, 8, 1, 0, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.build();
		entityManager.persist(event);
	}

	@Test
	void transitionCouponStatuses_activatesReadyCouponsPastIssueStartAt() {
		Coupon toActivate = createCoupon(CouponStatus.READY,
				LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1));
		Coupon notYetStarted = createCoupon(CouponStatus.READY,
				LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2));

		couponStatusSchedulerService.transitionCouponStatuses();
		entityManager.clear();

		assertThat(couponRepository.findById(toActivate.getCouponId()).orElseThrow().getStatus())
				.isEqualTo(CouponStatus.ACTIVE);
		assertThat(couponRepository.findById(notYetStarted.getCouponId()).orElseThrow().getStatus())
				.isEqualTo(CouponStatus.READY);
	}

	@Test
	void transitionCouponStatuses_endsActiveCouponsPastIssueEndAt() {
		Coupon toEnd = createCoupon(CouponStatus.ACTIVE,
				LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(1));
		Coupon stillActive = createCoupon(CouponStatus.ACTIVE,
				LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

		couponStatusSchedulerService.transitionCouponStatuses();
		entityManager.clear();

		assertThat(couponRepository.findById(toEnd.getCouponId()).orElseThrow().getStatus())
				.isEqualTo(CouponStatus.ENDED);
		assertThat(couponRepository.findById(stillActive.getCouponId()).orElseThrow().getStatus())
				.isEqualTo(CouponStatus.ACTIVE);
	}

	@Test
	void transitionCouponStatuses_leavesReadyCouponAsReadyWhenBothStartAndEndAlreadyPassed() {
		// 시작·종료 시각이 둘 다 이미 지난 READY 쿠폰은 ACTIVE를 거치지 않고 곧장 ENDED로
		// 자동 전이되면 안 된다 -> READY 그대로 남겨서 관리자가 확인하게 함
		Coupon staleReady = createCoupon(CouponStatus.READY,
				LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(1));

		couponStatusSchedulerService.transitionCouponStatuses();
		entityManager.clear();

		assertThat(couponRepository.findById(staleReady.getCouponId()).orElseThrow().getStatus())
				.isEqualTo(CouponStatus.READY);
	}

	@Test
	void transitionCouponStatuses_doesNotTouchSoldOutCoupons() {
		// SOLD_OUT은 이 스케줄러가 만드는 상태가 아니므로, 이미 SOLD_OUT인 쿠폰은 issueEndAt이
		// 지나도 안 건드려야 함
		Coupon soldOut = createCoupon(CouponStatus.SOLD_OUT,
				LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(1));

		couponStatusSchedulerService.transitionCouponStatuses();
		entityManager.clear();

		assertThat(couponRepository.findById(soldOut.getCouponId()).orElseThrow().getStatus())
				.isEqualTo(CouponStatus.SOLD_OUT);
	}

	@Test
	void transitionCouponStatuses_succeedsWhenNothingToTransition() {
		couponStatusSchedulerService.transitionCouponStatuses();
	}

	private Coupon createCoupon(CouponStatus status, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
		sequenceCounter++;

		Coupon coupon = Coupon.builder()
				.event(event)
				.name("상태 스케줄러 테스트 쿠폰" + sequenceCounter)
				.discountType(DiscountType.FIXED_AMOUNT)
				.discountValue(5_000)
				.minOrderAmount(10_000)
				.issueStartAt(issueStartAt)
				.issueEndAt(issueEndAt)
				.validDays(7)
				.build();
		entityManager.persist(coupon);
		entityManager.flush();

		// status는 빌더에 없는 기본값(READY)이라, READY가 아닌 상태로 테스트하려면 직접 UPDATE로 세팅한다.
		if (status != CouponStatus.READY) {
			entityManager.createQuery("UPDATE Coupon c SET c.status = :status WHERE c.couponId = :id")
					.setParameter("status", status)
					.setParameter("id", coupon.getCouponId())
					.executeUpdate();
			entityManager.clear();
			coupon = entityManager.find(Coupon.class, coupon.getCouponId());
		}

		return coupon;
	}
}
