package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 조건부 UPDATE(updateStatusIfMatches, cancelUsageIfMatches)가 실제 DB에서
 * 동시 요청 중 정확히 하나만 성공시키는지 검증
 *
 * @DataJpaTest는 테스트 메서드 전체를 하나의 트랜잭션/EntityManager로 감싸는데,
 * 이 트랜잭션은 스레드 세이프하지 않아 멀티스레드로 접근하면 상태가 꼬인다.
 * 그래서 CouponIssueLuaServiceIntegrationTest와 동일하게 @SpringBootTest를 쓴다.
 * 대신 자동 롤백이 없으므로 tearDown에서 직접 데이터를 정리한다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@SpringBootTest
class CouponIssueConcurrencyIntegrationTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CouponIssueUseServiceImpl couponIssueUseService;

	@Autowired
	private CouponIssueCancelServiceImpl couponIssueCancelService;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private AppUser admin;
	private Event event;
	private Coupon coupon;
	private AppUser issuedUser;
	private Long issuedUserId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> setUpData());
	}

	private void setUpData() {
		admin = AppUser.builder()
				.name("관리자")
				.email("concurrency-admin@test.com")
				.phone("010-1111-1111")
				.role(UserRole.ROLE_ADMIN)
				.build();
		entityManager.persist(admin);

		event = Event.builder()
				.createdBy(admin)
				.name("동시성 테스트 이벤트")
				.description("concurrency test")
				.openAt(LocalDateTime.of(2026, 8, 1, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.build();
		entityManager.persist(event);

		coupon = Coupon.builder()
				.event(event)
				.name("동시성 테스트 쿠폰")
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

		issuedUser = AppUser.builder()
				.name("발급받은유저")
				.email("concurrency-user@test.com")
				.phone("010-2222-2222")
				.role(UserRole.ROLE_MEMBER)
				.build();
		entityManager.persist(issuedUser);
		issuedUserId = issuedUser.getUserId();

		entityManager.flush();
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> tearDownData());
	}

	private void tearDownData() {
		entityManager.createNativeQuery(
				"DELETE h FROM coupon_issue_history h JOIN coupon_issue ci ON h.coupon_issue_id = ci.coupon_issue_id WHERE ci.coupon_id = :couponId")
				.setParameter("couponId", coupon.getCouponId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon_issue WHERE coupon_id = :couponId")
				.setParameter("couponId", coupon.getCouponId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon_stock WHERE coupon_id = :couponId")
				.setParameter("couponId", coupon.getCouponId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon WHERE coupon_id = :couponId")
				.setParameter("couponId", coupon.getCouponId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM event WHERE event_id = :eventId")
				.setParameter("eventId", event.getEventId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id IN (:ids)")
				.setParameter("ids", List.of(admin.getUserId(), issuedUserId))
				.executeUpdate();
	}

	@Test
	void onlyOneUseSucceedsWhenCalledConcurrently() throws Exception {
		CouponIssue couponIssue = createCouponIssue(IssueStatus.ISSUED, "CONC-USE-1");
		Long couponIssueId = couponIssue.getCouponIssueId();

		int threadCount = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		List<Future<Boolean>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < threadCount; i++) {
				futures.add(executor.submit(() -> {
					startLatch.await();
					try {
						couponIssueUseService.use(couponIssueId, issuedUserId);
						return true;
					} catch (GeneralException e) {
						return false;
					}
				}));
			}

			startLatch.countDown();

			long successCount = futures.stream()
					.filter(this::resultOf)
					.count();

			assertThat(successCount).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}

		entityManager.clear();
		CouponIssue reloaded = couponIssueRepository.findById(couponIssueId).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(IssueStatus.USED);
		assertThat(historyCountOf(couponIssueId)).isEqualTo(1L);
	}

	@Test
	void onlyOneCancelSucceedsWhenCalledConcurrently() throws Exception {
		CouponIssue couponIssue = createCouponIssue(IssueStatus.USED, "CONC-CANCEL-1");
		Long couponIssueId = couponIssue.getCouponIssueId();

		int threadCount = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		List<Future<Boolean>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < threadCount; i++) {
				futures.add(executor.submit(() -> {
					startLatch.await();
					try {
						couponIssueCancelService.cancelUsage(couponIssueId, issuedUserId);
						return true;
					} catch (GeneralException e) {
						return false;
					}
				}));
			}

			startLatch.countDown();

			long successCount = futures.stream()
					.filter(this::resultOf)
					.count();

			assertThat(successCount).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}

		entityManager.clear();
		CouponIssue reloaded = couponIssueRepository.findById(couponIssueId).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(IssueStatus.ISSUED);
		assertThat(historyCountOf(couponIssueId)).isEqualTo(1L);
	}

	private boolean resultOf(Future<Boolean> future) {
		try {
			return future.get();
		} catch (ExecutionException e) {
			if (e.getCause() instanceof GeneralException) {
				return false;
			}
			throw new RuntimeException(e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private long historyCountOf(Long couponIssueId) {
		return entityManager.createQuery(
						"SELECT COUNT(h) FROM CouponIssueHistory h WHERE h.couponIssue.couponIssueId = :id", Long.class)
				.setParameter("id", couponIssueId)
				.getSingleResult();
	}

	private CouponIssue createCouponIssue(IssueStatus status, String couponCode) {
		return transactionTemplate.execute(txStatus -> {
			CouponIssue issue = CouponIssue.builder()
					.coupon(coupon)
					.user(issuedUser)
					.sequenceNo(1)
					.couponCode(couponCode)
					.requestId("concurrency-test-request-" + couponCode)
					.status(status)
					.usedAt(status == IssueStatus.USED ? LocalDateTime.now() : null)
					.expiresAt(LocalDateTime.now().plusDays(1))
					.build();
			entityManager.persist(issue);
			entityManager.flush();
			return issue;
		});
	}
}