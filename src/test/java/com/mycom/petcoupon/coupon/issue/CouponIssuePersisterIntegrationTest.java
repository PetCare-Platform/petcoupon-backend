package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import com.mycom.petcoupon.notification.NotificationLogTestSupport;
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
		NotificationLogTestSupport.deleteByCouponId(entityManager, couponId);
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

	// coupon_code는 coupon_id 범위가 아니라 테이블 전체 기준 unique라, "CODE-1"처럼 고정 문자열을
	// 재사용하면 이전 실행의 leftover 행과 충돌할 수 있다. 매번 값이 달라지게 임의 접미사를 붙인다
	// (varchar(32) 한도 때문에 UUID는 앞 8자리만 사용).
	private CouponIssueEvent newEvent(String requestId, long sequenceNo) {
		return new CouponIssueEvent(
			couponId, user.getUserId(), requestId, sequenceNo,
			"CODE-" + sequenceNo + "-" + shortSuffix(), LocalDateTime.now().plusDays(7)
		);
	}

	private String shortSuffix() {
		return UUID.randomUUID().toString().substring(0, 8);
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
	void recordNotification_단독_호출은_예외를_전파하지만_Consumer_호출부에서는_새지_않는다() {
		// phone null인 유저 2명 — 하나는 persister.recordNotification()을 직접 호출하는 회귀 가드용,
		// 하나는 실제 진입점인 consumer.consume()을 통한 end-to-end 검증용이다. 같은 유저로 같은
		// 쿠폰에 두 번 발급하면 uk_issue_coupon_user(coupon_id, user_id) 위반이라 유저를 나눈다.
		AppUser directCallUser = AppUser.builder()
				.name("phone 없는 사용자(직접호출)")
				.email("no-phone-direct-" + UUID.randomUUID() + "@test.com")
				.phone(null)
				.build();
		AppUser consumerCallUser = AppUser.builder()
				.name("phone 없는 사용자(Consumer)")
				.email("no-phone-consumer-" + UUID.randomUUID() + "@test.com")
				.phone(null)
				.build();
		transactionTemplate.executeWithoutResult(status -> {
			entityManager.persist(directCallUser);
			entityManager.persist(consumerCallUser);
		});

		String directRequestId = "no-phone-direct-" + UUID.randomUUID();
		CouponIssueEvent directEvent = new CouponIssueEvent(
			couponId, directCallUser.getUserId(), directRequestId, 1L,
			"CODE-1-" + shortSuffix(), LocalDateTime.now().plusDays(7)
		);

		CouponIssue directCouponIssue = persister.persist(directEvent);

		entityManager.clear();

		// 발급 자체는 정상 커밋됐다 — phone null로 인한 알림 저장 실패가 여기까지 영향을 주지 않는다
		assertThat(couponIssueRepository.existsByRequestId(directRequestId)).isTrue();

		// recordNotification()을 단독 호출하면 예외가 그대로 전파된다 — notificationLogRepository.save()가
		// 실패하는 순간 JPA 스펙상 트랜잭션이 rollback-only로 마킹되고, @Transactional 프록시가 메서드
		// 반환 후 커밋을 시도하다가 실패해 예외를 던진다(실측: UnexpectedRollbackException). 메서드 안에서
		// 삼키려 해도 이 커밋 실패는 메서드 몸통 바깥이라 못 막는다 — CouponIssuePersister.recordNotification()
		// 주석 참고. 이 단언은 "내부 try/catch로 다시 삼키려는 시도"가 재발하면 실패하도록 남겨둔 회귀 가드다.
		assertThatThrownBy(() -> persister.recordNotification(directCouponIssue))
				.isInstanceOfAny(
						org.springframework.dao.DataIntegrityViolationException.class,
						org.springframework.transaction.UnexpectedRollbackException.class
				);

		entityManager.clear();
		assertThat(notificationCountOf(couponId)).isZero();

		// 실제 안전장치는 호출부(CouponIssueEventConsumer)에 있다 — 이게 진짜 프로덕션 진입점이고,
		// Kafka 리스너까지 예외가 새지 않아야 무한 재시도로 이어지지 않는다.
		String consumerRequestId = "no-phone-consumer-" + UUID.randomUUID();
		CouponIssueEvent consumerEvent = new CouponIssueEvent(
			couponId, consumerCallUser.getUserId(), consumerRequestId, 2L,
			"CODE-2-" + shortSuffix(), LocalDateTime.now().plusDays(7)
		);

		Throwable thrownFromConsumer = catchThrowable(() -> consumer.consume(consumerEvent));

		assertThat(thrownFromConsumer).isNull();

		entityManager.clear();

		// 발급은 그대로 남아있고, 알림 로그만 안 남아야 한다
		assertThat(couponIssueRepository.existsByRequestId(consumerRequestId)).isTrue();
		assertThat(notificationCountOf(couponId)).isZero();

		// tearDownData()가 couponId 기준으로 coupon_issue를 지우기 전에, 이 유저들을 참조하는
		// coupon_issue_history/coupon_issue를 먼저 지워야 app_user 삭제가 FK 위반 없이 끝난다
		transactionTemplate.executeWithoutResult(status -> {
			entityManager.createNativeQuery(
					"DELETE h FROM coupon_issue_history h JOIN coupon_issue ci ON h.coupon_issue_id = ci.coupon_issue_id WHERE ci.request_id IN :requestIds")
					.setParameter("requestIds", List.of(directRequestId, consumerRequestId))
					.executeUpdate();
			entityManager.createNativeQuery("DELETE FROM coupon_issue WHERE request_id IN :requestIds")
					.setParameter("requestIds", List.of(directRequestId, consumerRequestId))
					.executeUpdate();
			entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id IN :userIds")
					.setParameter("userIds", List.of(directCallUser.getUserId(), consumerCallUser.getUserId()))
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
