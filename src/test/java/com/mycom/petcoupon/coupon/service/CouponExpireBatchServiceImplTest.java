package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 네이티브 SQL(INSERT ... SELECT)은 앱 기동 시점에 검증되지 않고 실행할 때 터진다.
 * Mock으로는 오타나 컬럼명 불일치를 못 잡으므로 실제 DB로 검증한다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest(properties = "coupon.expire.chunk-size=3")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CouponExpireBatchServiceImpl.class)
class CouponExpireBatchServiceImplTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CouponExpireBatchServiceImpl couponExpireBatchService;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	private Coupon coupon;
	private int sequenceCounter = 0;

	@BeforeEach
	void setUp() {
		AppUser user = AppUser.builder()
				.name("테스트회원")
				.email("expire-test@test.com")
				.phone("010-1234-5678")
				.role(UserRole.ROLE_MEMBER)
				.build();
		entityManager.persist(user);

		Event event = Event.builder()
				.createdBy(user)
				.name("만료 배치 테스트 이벤트")
				.description("expire batch")
				.openAt(LocalDateTime.of(2026, 8, 1, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.build();
		entityManager.persist(event);

		coupon = Coupon.builder()
				.event(event)
				.name("만료 배치 테스트 쿠폰")
				.discountType(DiscountType.FIXED_AMOUNT)
				.discountValue(5_000)
				.minOrderAmount(10_000)
				.issueStartAt(LocalDateTime.of(2026, 8, 1, 9, 0))
				.issueEndAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.validDays(7)
				.build();
		entityManager.persist(coupon);

		CouponStock stock = CouponStock.builder()
				.coupon(coupon)
				.totalQuantity(100)
				.build();
		entityManager.persist(stock);

		entityManager.flush();
	}

	@Test
	void expireOverdueCoupons_expiresOnlyIssuedAndPastDeadline() {
		CouponIssue expiredIssue = createCouponIssue(IssueStatus.ISSUED, LocalDateTime.now().minusDays(1), "EXP-EXPIRED");
		CouponIssue notYetExpiredIssue = createCouponIssue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(1), "EXP-NOTYET");
		CouponIssue usedButExpiredIssue = createCouponIssue(IssueStatus.USED, LocalDateTime.now().minusDays(1), "EXP-USED");

		couponExpireBatchService.expireOverdueCoupons();
		entityManager.clear();

		CouponIssue reloadedExpired = couponIssueRepository.findById(expiredIssue.getCouponIssueId()).orElseThrow();
		CouponIssue reloadedNotYet = couponIssueRepository.findById(notYetExpiredIssue.getCouponIssueId()).orElseThrow();
		CouponIssue reloadedUsed = couponIssueRepository.findById(usedButExpiredIssue.getCouponIssueId()).orElseThrow();

		assertThat(reloadedExpired.getStatus()).isEqualTo(IssueStatus.EXPIRED);
		assertThat(reloadedNotYet.getStatus()).isEqualTo(IssueStatus.ISSUED);
		assertThat(reloadedUsed.getStatus()).isEqualTo(IssueStatus.USED);

		Long historyCount = entityManager.createQuery(
						"SELECT COUNT(h) FROM CouponIssueHistory h WHERE h.couponIssue.couponIssueId = :id", Long.class)
				.setParameter("id", expiredIssue.getCouponIssueId())
				.getSingleResult();
		assertThat(historyCount).isEqualTo(1L);
	}

	@Test
	void expireOverdueCoupons_succeedsWhenNothingToExpire() {
		couponExpireBatchService.expireOverdueCoupons();
	}

	@Test
	void expireOverdueCoupons_processesAllRowsAcrossMultipleChunks() {
		// chunk-size=3인데 대상이 7건이라, 3+3+1 세 번의 청크로 나뉘어 처리돼야 함
		List<CouponIssue> issues = IntStream.rangeClosed(1, 7)
				.mapToObj(i -> createCouponIssue(IssueStatus.ISSUED, LocalDateTime.now().minusDays(1), "EXP-CHUNK-" + i))
				.toList();

		couponExpireBatchService.expireOverdueCoupons();
		entityManager.clear();

		for (CouponIssue issue : issues) {
			CouponIssue reloaded = couponIssueRepository.findById(issue.getCouponIssueId()).orElseThrow();
			assertThat(reloaded.getStatus()).isEqualTo(IssueStatus.EXPIRED);
		}
	}

	private CouponIssue createCouponIssue(IssueStatus status, LocalDateTime expiresAt, String couponCode) {
		sequenceCounter++;

		AppUser issuedUser = AppUser.builder()
				.name("발급테스트회원" + sequenceCounter)
				.email("expire-test-" + sequenceCounter + "@test.com")
				.phone("010-9999-" + String.format("%04d", sequenceCounter))
				.role(UserRole.ROLE_MEMBER)
				.build();
		entityManager.persist(issuedUser);

		CouponIssue issue = CouponIssue.builder()
				.coupon(coupon)
				.user(issuedUser)
				.sequenceNo(sequenceCounter)
				.couponCode(couponCode)
				.requestId("expire-test-request-" + couponCode)
				.status(status)
				.expiresAt(expiresAt)
				.build();
		entityManager.persist(issue);
		entityManager.flush();
		return issue;
	}
}
