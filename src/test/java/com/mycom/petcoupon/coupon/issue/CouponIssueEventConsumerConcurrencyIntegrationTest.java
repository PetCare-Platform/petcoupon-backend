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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueEventConsumer;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.repository.CouponIssueHistoryRepository;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.notification.NotificationLogTestSupport;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Kafka는 at-least-once 전달을 보장하므로 같은 이벤트가 재전달/동시 처리될 수 있다 — 스펙의
 * "동일한 상태 변경 요청이 반복·동시에 발생해도 한 번만 반영돼야 한다"가 정확히 이 지점을 말한다.
 * 지금까지 이 경로(CouponIssueEventConsumer의 재전달 dedup)는 Mockito로 순서를 미리 프로그램한
 * 시나리오로만 검증돼서, 실제 DB 유니크 제약/락 경합에서도 진짜로 그렇게 동작하는지 증명한 적이
 * 없었다 — 실제 스레드 동시성 + 실제 MySQL로 검증한다.
 *
 * CouponIssueConcurrencyIntegrationTest(쿠폰 사용/취소 동시성)와 동일한 패턴
 * (ExecutorService + CountDownLatch, @SpringBootTest + EntityManager 수동 정리)을 따른다.
 *
 * 겸사겸사 "이력과 재고가 어떤 상황에서도 어긋나지 않아야 한다"(정합성 스펙)도 같이 검증한다 —
 * 동시/중복 처리 후 issued_quantity == coupon_issue 건수 == history 건수가 항상 맞는지, 그리고
 * persist() 트랜잭션이 중간에 실패하면 이미 flush된 내용까지 포함해 전부 롤백되는지.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@SpringBootTest(properties = {
	// 이 테스트는 이벤트/쿠폰 상태 스케줄러와 무관하므로 둘 다 꺼서 경합 자체를 차단
	"event.status.scheduler.enabled=false",
	"coupon.status.enabled=false"
})
class CouponIssueEventConsumerConcurrencyIntegrationTest {

	private static final int INITIAL_STOCK = 100;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private CouponIssueEventConsumer consumer;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@Autowired
	private CouponStockRepository couponStockRepository;

	@Autowired
	private CouponIssueHistoryRepository couponIssueHistoryRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactionTemplate;

	private AppUser user;
	private Event event;
	private Coupon coupon;
	private Long couponId;
	private final List<Long> extraUserIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> setUpData());
	}

	private void setUpData() {
		LocalDateTime now = LocalDateTime.now();

		user = AppUser.builder()
				.name("동시성 테스트 사용자")
				.email("consumer-concurrency-" + UUID.randomUUID() + "@test.com")
				.phone("010-1234-5678")
				.build();
		entityManager.persist(user);

		event = Event.builder()
				.createdBy(user)
				.name("Consumer 동시성 테스트 이벤트")
				.description("설명")
				.openAt(now.minusHours(1))
				.closeAt(now.plusDays(1))
				.build();
		entityManager.persist(event);

		coupon = Coupon.builder()
				.event(event)
				.name("Consumer 동시성 테스트 쿠폰")
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
		// #119(쿠폰 발급 알림 로그)에서 coupon_issue를 FK로 무는 notification_log가 추가돼서,
		// coupon_issue 삭제보다 먼저 지워야 한다.
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
		List<Long> allUserIds = new ArrayList<>(extraUserIds);
		allUserIds.add(user.getUserId());
		entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id IN (:userIds)")
				.setParameter("userIds", allUserIds)
				.executeUpdate();
	}

	private CouponIssueEvent newEvent(String requestId, long sequenceNo) {
		return newEvent(requestId, sequenceNo, user.getUserId());
	}

	private CouponIssueEvent newEvent(String requestId, long sequenceNo, Long userId) {
		return new CouponIssueEvent(
			couponId, userId, requestId, sequenceNo,
			"CODE-" + sequenceNo, LocalDateTime.now().plusDays(7)
		);
	}

	// coupon_issue.uk_issue_coupon_user(1인 1쿠폰) 제약 때문에 "서로 다른 이벤트" 테스트는
	// 유저도 서로 달라야 한다 — 같은 유저로 여러 건을 만들면 이 제약에 걸려 의도와 다른 예외가 난다
	private List<Long> createUsers(int count) {
		List<Long> userIds = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			AppUser distinctUser = AppUser.builder()
					.name("동시성 테스트 사용자 " + i)
					.email("consumer-concurrency-distinct-" + UUID.randomUUID() + "@test.com")
					.phone("010-0000-0000")
					.build();
			entityManager.persist(distinctUser);
			userIds.add(distinctUser.getUserId());
		}

		entityManager.flush();
		return userIds;
	}

	private long historyCountOf(Long couponId) {
		return couponIssueHistoryRepository.findAll().stream()
				.filter(h -> h.getCouponId() == couponId)
				.count();
	}

	@Test
	void 동일_이벤트가_동시에_여러번_전달돼도_발급은_한_건만_생성된다() throws Exception {
		String requestId = "concurrent-redelivery-" + UUID.randomUUID();
		CouponIssueEvent event = newEvent(requestId, 1L);

		int threadCount = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		List<Future<Exception>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < threadCount; i++) {
				futures.add(executor.submit(() -> {
					startLatch.await();
					try {
						consumer.consume(event);
						return null;
					} catch (Exception e) {
						return e;
					}
				}));
			}

			startLatch.countDown();

			List<Exception> thrown = futures.stream()
					.map(this::resultOf)
					.filter(e -> e != null)
					.collect(Collectors.toList());

			// 재전달/동시 처리 중 하나가 "진짜" 저장을 하고 나머지는 dedup 분기(이미 저장됨 확인)를
			// 타야 하므로, consume() 밖으로 예외가 새어나가는 스레드가 하나도 없어야 한다
			assertThat(thrown).isEmpty();
		} finally {
			executor.shutdownNow();
		}

		entityManager.clear();

		long issueCount = couponIssueRepository.findAll().stream()
				.filter(ci -> ci.getRequestId().equals(requestId))
				.count();
		assertThat(issueCount).isEqualTo(1L);

		CouponStock stock = couponStockRepository.findById(couponId).orElseThrow();
		assertThat(stock.getIssuedQuantity()).isEqualTo(1);
		assertThat(stock.getRemainingQuantity()).isEqualTo(INITIAL_STOCK - 1);

		assertThat(historyCountOf(couponId)).isEqualTo(1L);
	}

	@Test
	void 이미_저장된_이벤트가_나중에_다시_전달돼도_추가_반영_없이_스킵된다() {
		String requestId = "sequential-redelivery-" + UUID.randomUUID();
		CouponIssueEvent event = newEvent(requestId, 1L);

		consumer.consume(event);
		consumer.consume(event); // 재전달 시뮬레이션 — 오프셋 커밋 전 재수신 등

		entityManager.clear();

		long issueCount = couponIssueRepository.findAll().stream()
				.filter(ci -> ci.getRequestId().equals(requestId))
				.count();
		assertThat(issueCount).isEqualTo(1L);

		CouponStock stock = couponStockRepository.findById(couponId).orElseThrow();
		assertThat(stock.getIssuedQuantity()).isEqualTo(1);

		assertThat(historyCountOf(couponId)).isEqualTo(1L);
	}

	@Test
	void 서로_다른_이벤트가_동시에_처리돼도_모두_정상_저장되고_재고가_정확히_반영된다() throws Exception {
		int eventCount = 10;
		String requestPrefix = "concurrent-distinct-" + UUID.randomUUID() + "-";

		List<Long> userIds = transactionTemplate.execute(status -> createUsers(eventCount));
		extraUserIds.addAll(userIds);

		List<CouponIssueEvent> events = IntStream.rangeClosed(1, eventCount)
				.mapToObj(i -> newEvent(requestPrefix + i, i, userIds.get(i - 1)))
				.collect(Collectors.toList());

		ExecutorService executor = Executors.newFixedThreadPool(eventCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		List<Future<Exception>> futures = new ArrayList<>();

		try {
			for (CouponIssueEvent event : events) {
				futures.add(executor.submit(() -> {
					startLatch.await();
					try {
						consumer.consume(event);
						return null;
					} catch (Exception e) {
						return e;
					}
				}));
			}

			startLatch.countDown();

			List<Exception> thrown = futures.stream()
					.map(this::resultOf)
					.filter(e -> e != null)
					.collect(Collectors.toList());

			assertThat(thrown).isEmpty();
		} finally {
			executor.shutdownNow();
		}

		entityManager.clear();

		long issueCount = couponIssueRepository.findAll().stream()
				.filter(ci -> ci.getRequestId().startsWith(requestPrefix))
				.count();
		assertThat(issueCount).isEqualTo(eventCount);

		// coupon_stock 원자적 UPDATE(increaseIssuedQuantity)가 서로 다른 트랜잭션의 동시 쓰기에서도
		// lost update 없이 전부 반영되는지 확인 — 이게 깨지면 issuedQuantity가 eventCount보다 작게 나온다
		CouponStock stock = couponStockRepository.findById(couponId).orElseThrow();
		assertThat(stock.getIssuedQuantity()).isEqualTo(eventCount);
		assertThat(stock.getRemainingQuantity()).isEqualTo(INITIAL_STOCK - eventCount);

		assertThat(historyCountOf(couponId)).isEqualTo(eventCount);
	}

	@Test
	void 재고_소진_시점에_실패하면_발급도_이력도_재고도_전혀_반영되지_않는다() {
		// coupon_issue.saveAndFlush()는 즉시 DB에 INSERT를 내려보내지만(flush), 그 뒤 재고 증가가
		// 0건이라 IllegalStateException이 던져지면 @Transactional 경계에서 롤백된다 — "이미 flush된
		// INSERT가 실제로도 커밋 없이 사라지는지"는 Mockito로는 증명 못 하고 실제 DB로만 확인 가능하다.
		transactionTemplate.executeWithoutResult(status ->
				entityManager.createNativeQuery(
						"UPDATE coupon_stock SET issued_quantity = total_quantity, remaining_quantity = 0 WHERE coupon_id = :couponId")
						.setParameter("couponId", couponId)
						.executeUpdate()
		);
		entityManager.clear();

		String requestId = "stock-exhausted-" + UUID.randomUUID();
		CouponIssueEvent event = newEvent(requestId, 1L);

		assertThatThrownBy(() -> consumer.consume(event))
				.isInstanceOf(IllegalStateException.class);

		entityManager.clear();

		boolean issueExists = couponIssueRepository.findAll().stream()
				.anyMatch(ci -> ci.getRequestId().equals(requestId));
		assertThat(issueExists).isFalse();

		CouponStock stock = couponStockRepository.findById(couponId).orElseThrow();
		assertThat(stock.getIssuedQuantity()).isEqualTo(INITIAL_STOCK);
		assertThat(stock.getRemainingQuantity()).isZero();

		assertThat(historyCountOf(couponId)).isZero();
	}

	private Exception resultOf(Future<Exception> future) {
		try {
			return future.get();
		} catch (Exception e) {
			return e;
		}
	}
}
