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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
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
import com.mycom.petcoupon.notification.entity.NotificationLog;
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

	// Consumer 내부에서 매번 새로 생성되는 CouponIssue는 PK를 미리 알 수 없어 uk_noti_issue_channel
	// 중복으로 실패를 유도할 수 없다 — 그 케이스만 특정 유저의 save()에 한해 예외를 강제한다.
	@MockitoSpyBean
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
		// phone 값에 의존하지 않는다 — app_user의 email/phone NOT NULL 제약이 마이그레이션 없이
		// DB에만(예: ROLE_MEMBER 전용 CHECK) 걸려 있는 환경도 있어, phone-null 픽스처로는 실패
		// 재현을 항상 보장할 수 없다. phone과 무관한 두 가지 실패 트리거를 쓴다.
		AppUser directCallUser = AppUser.builder()
				.name("알림 중복 사용자(직접호출)")
				.email("noti-dup-direct-" + UUID.randomUUID() + "@test.com")
				.phone("010-1111-1111")
				.build();
		AppUser consumerCallUser = AppUser.builder()
				.name("알림 중복 사용자(Consumer)")
				.email("noti-dup-consumer-" + UUID.randomUUID() + "@test.com")
				.phone("010-2222-2222")
				.build();
		transactionTemplate.executeWithoutResult(status -> {
			entityManager.persist(directCallUser);
			entityManager.persist(consumerCallUser);
		});

		// (1) 단독 호출: NotificationLog 자신의 유니크 제약 uk_noti_issue_channel(coupon_issue_id,
		// channel)을 이용한다 — 같은 발급 건에 SMS 알림을 두 번 기록하면 두 번째 save()가 실패한다.
		String directRequestId = "noti-dup-direct-" + UUID.randomUUID();
		CouponIssueEvent directEvent = new CouponIssueEvent(
			couponId, directCallUser.getUserId(), directRequestId, 1L,
			"CODE-1-" + shortSuffix(), LocalDateTime.now().plusDays(7)
		);

		CouponIssue directCouponIssue = persister.persist(directEvent);
		persister.recordNotification(directCouponIssue);

		entityManager.clear();
		assertThat(couponIssueRepository.existsByRequestId(directRequestId)).isTrue();
		assertThat(notificationCountOf(couponId)).isEqualTo(1L);

		// 커밋 실패(rollback-only 마킹 후 UnexpectedRollbackException)는 메서드 몸통 바깥에서
		// 일어나 내부 try/catch로 못 막는다 — CouponIssuePersister.recordNotification() 주석 참고.
		assertThatThrownBy(() -> persister.recordNotification(directCouponIssue))
				.isInstanceOfAny(DataIntegrityViolationException.class, UnexpectedRollbackException.class);

		entityManager.clear();
		assertThat(notificationCountOf(couponId)).isEqualTo(1L); // 실패한 재시도는 건수를 늘리지 않는다

		// (2) 실제 안전장치는 호출부(CouponIssueEventConsumer)에 있다 — Kafka 리스너까지 예외가 새지
		// 않아야 무한 재시도로 이어지지 않는다. consume()이 생성하는 CouponIssue는 PK를 미리 알 수
		// 없어 (1)과 같은 방법을 못 쓰므로, 이 유저의 save()만 예외를 던지도록 스텁한다.
		Mockito.doThrow(new DataIntegrityViolationException("simulated notification save failure"))
				.when(notificationLogRepository)
				.save(Mockito.<NotificationLog>argThat(log -> log != null && log.getUser() != null
						&& log.getUser().getUserId().equals(consumerCallUser.getUserId())));

		String consumerRequestId = "noti-dup-consumer-" + UUID.randomUUID();
		CouponIssueEvent consumerEvent = new CouponIssueEvent(
			couponId, consumerCallUser.getUserId(), consumerRequestId, 2L,
			"CODE-2-" + shortSuffix(), LocalDateTime.now().plusDays(7)
		);

		Throwable thrownFromConsumer = catchThrowable(() -> consumer.consume(consumerEvent));

		assertThat(thrownFromConsumer).isNull();

		entityManager.clear();

		// 발급은 그대로 남아있고, 알림 로그만 안 남아야 한다(직접 호출 케이스의 1건에서 늘지 않는다)
		assertThat(couponIssueRepository.existsByRequestId(consumerRequestId)).isTrue();
		assertThat(notificationCountOf(couponId)).isEqualTo(1L);

		// tearDownData()가 couponId 기준으로 coupon_issue를 지우기 전에, 이 유저들을 참조하는
		// notification_log(직접 호출 케이스는 실제로 1건 남아있다)/coupon_issue_history/coupon_issue를
		// 먼저 지워야 app_user 삭제가 FK 위반 없이 끝난다
		transactionTemplate.executeWithoutResult(status -> {
			entityManager.createNativeQuery(
					"DELETE n FROM notification_log n JOIN coupon_issue ci ON n.coupon_issue_id = ci.coupon_issue_id WHERE ci.request_id IN :requestIds")
					.setParameter("requestIds", List.of(directRequestId, consumerRequestId))
					.executeUpdate();
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
