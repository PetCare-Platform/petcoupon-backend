package com.mycom.petcoupon.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 대시보드 요약 집계(#172)용 sumStock() 검증 — SUM 3개를 한 프로젝션으로 받는 쿼리라,
 * JPQL 문법과 COALESCE·Long 매핑이 실제로 맞물려 도는지는 목으로는 확인 못 한다. READY
 * 쿠폰을 제외하는 필터(PR 리뷰 반영)도 실제 조건부 UPDATE(activateCoupons)로 상태를
 * 전이시켜 검증한다 — Coupon 엔티티에 status를 직접 세팅하는 세터가 없다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponStockRepositoryTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CouponStockRepository couponStockRepository;

	@Autowired
	private CouponRepository couponRepository;

	private Event event;

	@BeforeEach
	void setUp() {
		AppUser user = AppUser.builder()
				.name("CouponStockRepository 테스트 사용자")
				.email("coupon-stock-repo-test@test.com")
				.phone("010-1234-5678")
				.build();
		entityManager.persist(user);

		LocalDateTime now = LocalDateTime.now();
		event = Event.builder()
				.createdBy(user)
				.name("CouponStockRepository 테스트 이벤트")
				.description("설명")
				.openAt(now.minusHours(1))
				.closeAt(now.plusDays(1))
				.build();
		entityManager.persist(event);
	}

	// sumStock()은 다른 테스트/실행이 남긴 재고가 섞여 있을 수 있는 전체 집계라, 절대값이
	// 아니라 델타로 검증한다(EventRepositoryTest·CouponRepositoryTest와 같은 이유).
	@Test
	void sumStock는_ACTIVE_SOLD_OUT_ENDED_쿠폰의_재고_합계만_정확히_구한다() {
		CouponStockSummary before = couponStockRepository.sumStock();

		LocalDateTime now = LocalDateTime.now();

		// 발급 기간이 이미 시작된 두 쿠폰 — activateCoupons로 ACTIVE가 돼서 집계에 잡혀야 한다.
		Coupon couponA = persistCoupon("sumStock-active-a", now.minusMinutes(10), now.plusHours(1));
		CouponStock stockA = CouponStock.builder().coupon(couponA).totalQuantity(100).build();
		entityManager.persist(stockA);

		Coupon couponB = persistCoupon("sumStock-active-b", now.minusMinutes(10), now.plusHours(1));
		CouponStock stockB = CouponStock.builder().coupon(couponB).totalQuantity(50).build();
		entityManager.persist(stockB);

		// 발급 기간이 아직 시작 전인 쿠폰 — activateCoupons 대상이 아니라 READY로 남는다.
		// totalQuantity를 크게(9999) 잡아서, 필터가 빠져 있으면(버그가 나면) 델타 검증에서
		// 확실히 어긋나게 만든다.
		Coupon couponReady = persistCoupon("sumStock-ready", now.plusDays(1), now.plusDays(2));
		CouponStock stockReady = CouponStock.builder().coupon(couponReady).totalQuantity(9_999).build();
		entityManager.persist(stockReady);

		entityManager.flush();
		couponRepository.activateCoupons(now);
		entityManager.clear();

		// increaseIssuedQuantity로 issuedQuantity/remainingQuantity를 실제로 갈라놓아야
		// totalQuantity만 그대로 합산하는 버그(예: issuedQuantity 컬럼을 잘못 참조)가 있어도
		// 이 테스트가 잡아낸다 — 세 값이 전부 같은 숫자면 컬럼을 바꿔써도 테스트가 안 깨진다.
		couponStockRepository.increaseIssuedQuantity(couponA.getCouponId());
		couponStockRepository.increaseIssuedQuantity(couponA.getCouponId());
		couponStockRepository.increaseIssuedQuantity(couponB.getCouponId());

		entityManager.clear();

		CouponStockSummary after = couponStockRepository.sumStock();

		// READY인 sumStock-ready(9999)가 안 섞여 있어야 정확히 100+50=150이 나온다.
		assertThat(after.getTotalQuantity() - before.getTotalQuantity()).isEqualTo(150L);
		assertThat(after.getIssuedQuantity() - before.getIssuedQuantity()).isEqualTo(3L);
		assertThat(after.getRemainingQuantity() - before.getRemainingQuantity()).isEqualTo(147L);
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
