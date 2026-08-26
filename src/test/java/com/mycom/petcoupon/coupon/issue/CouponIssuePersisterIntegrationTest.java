package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueEventConsumer;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssuePersister;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.notification.repository.NotificationLogRepository;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * #119 알림 로그 기록이 (1) Kafka 재전달로 같은 이벤트가 동시에 여러 번 처리돼도 중복 기록되지
 * 않는지, (2) 발급 자체가 롤백되는 상황에서 알림 로그만 남는 반쪽 상태가 생기지 않는지를
 * 실제 스레드 동시성 + 실제 MySQL로 검증한다. recordNotification()은 persist()와 별도
 * 트랜잭션으로 분리돼있어(phone null 등으로 알림 저장이 실패해도 발급 자체는 롤백되지 않게 하기
 * 위함), Consumer가 persist() 성공 이후 별도로 호출한다 — "될 것 같다"가 아니라 실측으로 확인한다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@SpringBootTest(properties = {
	"event.status.scheduler.enabled=false",
	"coupon.status.enabled=false"
})
class CouponIssuePersisterIntegrationTest {

	private static final int INITIAL_STOCK = 100;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CouponIssuePersister persister;

	@Autowired
	private CouponIssueEventConsumer consumer;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@Autowired
	private CouponStockRepository couponStockRepository;

	@Autowired
	private NotificationLogRepository notificationLogRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private AppUser user;
	private Event event;
	private Coupon coupon;
	private Long couponId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> setUpData());
	}

	private void setUpData() {
		LocalDateTime now = LocalDateTime.now();

		user = AppUser.builder()
				.name("알림 테스트 사용자")
				.email("notification-test-" + UUID.randomUUID() + "@test.com")
				.phone("010-1234-5678")
				.build();
		entityManager.persist(user);

		event = Event.builder()
				.createdBy(user)
				.name("알림 테스트 이벤트")
				.description("설명")
				.openAt(now.minusHours(1))
				.closeAt(now.plusDays(1))
				.build();
		entityManager.persist(event);

		coupon = Coupon.builder()
				.event(event)
				.name("알림 테스트 쿠폰")
				.discountType(DiscountType.values()[0])
				.discountValue(1_000)
				.minOrderAmount(10_000)
				.maxDiscountAmount(null)
				.issueStartAt(now.minusMinutes(10))
				.issueEndAt(now.plusHours(1))
				.validDays(7)
				.build();
		entityManager.persist(coupon);
		couponId = coupon.getCouponId();

		CouponStock stock = CouponStock.builder().coupon(coupon).totalQuantity(INITIAL_STOCK).build();
		entityManager.persist(stock);

		entityManager.flush();
	}

	@AfterEach
	void tearDown() {
		transactionTemplate.executeWithoutResult(status -> tearDownData());
	}

	private void tearDownData() {
		entityManager.createNativeQuery(
				"DELETE n FROM notification_log n JOIN coupon_issue ci ON n.coupon_issue_id = ci.coupon_issue_id WHERE ci.coupon_id = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
		entityManager.createNativeQuery(
				"DELETE h FROM coupon_issue_history h JOIN coupon_issue ci ON h.coupon_issue_id = ci.coupon_issue_id WHERE ci.coupon_id = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon_issue WHERE coupon_id = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon_stock WHERE coupon_id = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM coupon WHERE coupon_id = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM event WHERE event_id = :eventId")
				.setParameter("eventId", event.getEventId())
				.executeUpdate();
		entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id = :userId")
				.setParameter("userId", user.getUserId())
				.executeUpdate();
	}

	private CouponIssueEvent newEvent(String requestId, long sequenceNo) {
		return new CouponIssueEvent(
			couponId, user.getUserId(), requestId, sequenceNo,
			"CODE-" + sequenceNo, LocalDateTime.now().plusDays(7)
		);
	}

	// NotificationLog.couponIssue, CouponIssue.coupon 둘 다 LAZY라 세션 밖에서 접근하면
	// LazyInitializationException이 발생하므로 트랜잭션 안에서 조회한다
	private long notificationCountOf(Long couponId) {
		return transactionTemplate.execute(status ->
				notificationLogRepository.findAll().stream()
						.filter(n -> n.getCouponIssue() != null
								&& n.getCouponIssue().getCoupon().getCouponId().equals(couponId))
						.count()
		);
	}

	@Test
	void 단일_발급_성공시_알림_로그가_정상_기록된다() {
		String requestId = "notification-single-" + UUID.randomUUID();
		CouponIssueEvent event = newEvent(requestId, 1L);

		CouponIssue couponIssue = persister.persist(event);
		persister.recordNotification(couponIssue);

		entityManager.clear();

		assertThat(notificationCountOf(couponId)).isEqualTo(1L);
	}

	@Test
	void phone이_없어도_발급은_정상_처리되고_알림_기록만_실패한다() {
		AppUser userWithoutPhone = AppUser.builder()
				.name("phone 없는 사용자")
				.email("no-phone-" + UUID.randomUUID() + "@test.com")
				.phone(null)
				.build();
		transactionTemplate.executeWithoutResult(status -> entityManager.persist(userWithoutPhone));

		String requestId = "notification-no-phone-" + UUID.randomUUID();
		CouponIssueEvent event = new CouponIssueEvent(
			couponId, userWithoutPhone.getUserId(), requestId, 1L, "CODE-1", LocalDateTime.now().plusDays(7)
		);

		CouponIssue couponIssue = persister.persist(event);

		entityManager.clear();

		// 발급 자체는 정상 커밋됐다 — phone null로 인한 알림 저장 실패가 여기까지 영향을 주지 않는다
		assertThat(couponIssueRepository.existsByRequestId(requestId)).isTrue();

		assertThatThrownBy(() -> persister.recordNotification(couponIssue))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

		entityManager.clear();

		// recordNotification()이 실패해도 이미 커밋된 발급(coupon_issue)은 그대로 남아있다
		assertThat(couponIssueRepository.existsByRequestId(requestId)).isTrue();
		assertThat(notificationCountOf(couponId)).isZero();

		// tearDownData()가 couponId 기준으로 coupon_issue를 지우기 전에, 이 유저를 참조하는
		// coupon_issue_history/coupon_issue를 먼저 지워야 app_user 삭제가 FK 위반 없이 끝난다
		transactionTemplate.executeWithoutResult(status -> {
			entityManager.createNativeQuery(
					"DELETE h FROM coupon_issue_history h JOIN coupon_issue ci ON h.coupon_issue_id = ci.coupon_issue_id WHERE ci.request_id = :requestId")
					.setParameter("requestId", requestId)
					.executeUpdate();
			entityManager.createNativeQuery("DELETE FROM coupon_issue WHERE request_id = :requestId")
					.setParameter("requestId", requestId)
					.executeUpdate();
			entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id = :userId")
					.setParameter("userId", userWithoutPhone.getUserId())
					.executeUpdate();
		});
	}

	@Test
	void 동일_이벤트가_동시에_여러번_처리돼도_알림_로그는_한_건만_생성된다() throws Exception {
		String requestId = "notification-redelivery-" + UUID.randomUUID();
		CouponIssueEvent event = newEvent(requestId, 1L);

		// Kafka는 같은 파티션의 같은 메시지를 여러 스레드에 동시에 주지 않는다 — 진짜 재전달은
		// 리밸런스 등으로 시간차를 두고 2~3번 겹치는 정도가 현실적인 상한이라, 그 규모로 검증한다.
		int threadCount = 3;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		List<Future<Void>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < threadCount; i++) {
				futures.add(executor.submit(() -> {
					startLatch.await();
					try {
						consumer.consume(event);
					} catch (Exception ignored) {
						// 재전달 dedup 자체(정확히 1건만 성공하는지)는
						// CouponIssueEventConsumerConcurrencyIntegrationTest(#112)가 이미 검증함 —
						// 여기서는 그 성공한 1건에 알림 로그가 정확히 1건만 붙는지만 확인한다
					}
					return null;
				}));
			}

			startLatch.countDown();
			for (Future<Void> future : futures) {
				future.get();
			}
		} finally {
			executor.shutdownNow();
		}

		entityManager.clear();

		long issueCount = couponIssueRepository.findAll().stream()
				.filter(ci -> ci.getRequestId().equals(requestId))
				.count();
		assertThat(issueCount).isEqualTo(1L);

		assertThat(notificationCountOf(couponId)).isEqualTo(1L);
	}

	@Test
	void 재고_소진으로_발급이_롤백되면_알림_로그도_남지_않는다() {
		transactionTemplate.executeWithoutResult(status ->
				entityManager.createNativeQuery(
						"UPDATE coupon_stock SET issued_quantity = total_quantity, remaining_quantity = 0 WHERE coupon_id = :couponId")
						.setParameter("couponId", couponId)
						.executeUpdate()
		);
		entityManager.clear();

		String requestId = "notification-rollback-" + UUID.randomUUID();
		CouponIssueEvent event = newEvent(requestId, 1L);

		assertThatThrownBy(() -> persister.persist(event))
				.isInstanceOf(IllegalStateException.class);

		entityManager.clear();

		assertThat(notificationCountOf(couponId)).isZero();
	}
}
