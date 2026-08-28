package com.mycom.petcoupon.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 대시보드 요약 집계(#172)용 countByStatus 검증.
 *
 * Coupon 엔티티는 status를 직접 세팅하는 세터가 없다(빌더도 항상 READY로 시작) —
 * READY -> ACTIVE 전이는 CouponStatusSchedulerServiceImpl이 쓰는 것과 같은 경로인
 * activateCoupons(now)로 실제 조건부 UPDATE를 태워서 만든다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponRepositoryTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CouponRepository couponRepository;

	private Event event;

	@BeforeEach
	void setUp() {
		AppUser user = AppUser.builder()
				.name("CouponRepository 테스트 사용자")
				.email("coupon-repo-test@test.com")
				.phone("010-1234-5678")
				.build();
		entityManager.persist(user);

		LocalDateTime now = LocalDateTime.now();
		event = Event.builder()
				.createdBy(user)
				.name("CouponRepository 테스트 이벤트")
				.description("설명")
				.openAt(now.minusHours(1))
				.closeAt(now.plusDays(1))
				.build();
		entityManager.persist(event);
	}

	// countByStatus()는 다른 테스트/실행이 남긴 쿠폰이 섞여 있을 수 있는 전체 집계라,
	// 절대값이 아니라 델타로 검증한다(EventRepositoryTest와 같은 이유).
	@Test
	void countByStatus는_해당_상태의_쿠폰_수만_정확히_센다() {
		long activeBefore = couponRepository.countByStatus(CouponStatus.ACTIVE);

		LocalDateTime now = LocalDateTime.now();
		// activateCoupons가 issueStartAt <= now < issueEndAt만 ACTIVE로 바꾸므로, 발급 기간이
		// 이미 시작된 쿠폰과 아직 시작 전인 쿠폰을 하나씩 만들어 델타가 정확히 1건만 늘게 한다.
		persistCoupon("countByStatus-active", now.minusMinutes(10), now.plusHours(1));
		persistCoupon("countByStatus-not-yet-started", now.plusDays(1), now.plusDays(2));

		entityManager.flush();
		couponRepository.activateCoupons(now);
		entityManager.clear();

		long activeAfter = couponRepository.countByStatus(CouponStatus.ACTIVE);

		assertThat(activeAfter - activeBefore).isEqualTo(1L);
	}

	private Coupon persistCoupon(String name, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
		Coupon coupon = Coupon.builder()
				.event(event)
				.name(name)
				.discountType(DiscountType.FIXED_AMOUNT)
				.discountValue(1_000)
				.minOrderAmount(10_000)
				.issueStartAt(issueStartAt)
				.issueEndAt(issueEndAt)
				.validDays(7)
				.build();
		entityManager.persist(coupon);
		return coupon;
	}
}
