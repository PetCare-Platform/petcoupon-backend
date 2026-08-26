package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueStreamProducer;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;


@SpringBootTest(properties = { 
	"coupon.issue.stream.enabled=true", "coupon.issue.outbox.enabled=true",
	"coupon.issue.outbox.publish-fixed-delay-ms=100", "coupon.issue.outbox.batch-size=10",
	"spring.kafka.consumer.auto-offset-reset=earliest",
	
	// 이 테스트는 이벤트/쿠폰 상태 스케줄러와 무관하므로 둘 다 꺼서 경합 자체를 차단
	"event.status.scheduler.enabled=false",
	"coupon.status.enabled=false"
})
class CouponIssuePipelineIntegrationTest {

	private static final int INITIAL_STOCK = 10;
	private static final long TIMEOUT_MILLIS = 15_000L;
	private static final long POLL_INTERVAL_MILLIS = 100L;

	@Autowired
	private CouponIssueStreamProducer streamProducer;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponStockRepository couponStockRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;
	
	@Autowired
	private IssueMessageRepository issueMessageRepository;
	
	@PersistenceContext
	private EntityManager entityManager;

	private TransactionTemplate transactionTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;
	
	private Long couponId;	
	private Long eventId;
	private Long userId;
	private String requestId;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		LocalDateTime now = LocalDateTime.now();

		AppUser user = appUserRepository.saveAndFlush(AppUser.builder().name("통합 테스트 사용자")
			.email("pipeline-" + UUID.randomUUID() + "@test.com").phone("01012345678").build());

		userId = user.getUserId();
		
		Event event = eventRepository.saveAndFlush(Event.builder().createdBy(user).name("통합 테스트 이벤트")
			.description("쿠폰 발급 파이프라인 테스트").openAt(now.minusHours(1)).closeAt(now.plusDays(1)).build());

		eventId = event.getEventId();
		
		Coupon coupon = couponRepository
			.saveAndFlush(Coupon.builder().event(event).name("통합 테스트 쿠폰").discountType(DiscountType.values()[0])
				.discountValue(1_000).minOrderAmount(10_000).maxDiscountAmount(null)
				.issueStartAt(now.minusMinutes(10)).issueEndAt(now.plusHours(1)).validDays(7).build());

		couponId = coupon.getCouponId();
		
		couponStockRepository.saveAndFlush(CouponStock.builder().coupon(coupon).totalQuantity(INITIAL_STOCK).build());
		
		requestId = "pipeline-" + UUID.randomUUID();

		redisTemplate.delete(List.of(stockKey(), applicantsKey(), sequenceKey(), requestSequenceKey()));

		redisTemplate.opsForValue().set(stockKey(), String.valueOf(INITIAL_STOCK));
	}

	@AfterEach
	void tearDown() {
		
		if (couponId != null) {
			redisTemplate.delete(List.of(stockKey(), applicantsKey(), sequenceKey(), requestSequenceKey()));
		}

		transactionTemplate.executeWithoutResult(status -> {
			if (couponId != null) {
				// coupon_issue를 참조하는 이력을 먼저 삭제
				entityManager.createNativeQuery("""
						DELETE FROM coupon_issue_history
						 WHERE coupon_id = :couponId
						""").setParameter("couponId", couponId).executeUpdate();

				entityManager.createNativeQuery("""
						DELETE FROM coupon_issue
						 WHERE coupon_id = :couponId
						""").setParameter("couponId", couponId).executeUpdate();

				entityManager.createNativeQuery("""
						DELETE FROM issue_message
						 WHERE coupon_id = :couponId
						""").setParameter("couponId", couponId).executeUpdate();

				entityManager.createNativeQuery("""
						DELETE FROM coupon_stock
						 WHERE coupon_id = :couponId
						""").setParameter("couponId", couponId).executeUpdate();

				entityManager.createNativeQuery("""
						DELETE FROM coupon
						 WHERE coupon_id = :couponId
						""").setParameter("couponId", couponId).executeUpdate();
			}

			if (eventId != null) {
				entityManager.createNativeQuery("""
						DELETE FROM event_status_history
						 WHERE event_id = :eventId
						""").setParameter("eventId", eventId).executeUpdate();

				entityManager.createNativeQuery("""
						DELETE FROM event
						 WHERE event_id = :eventId
						""").setParameter("eventId", eventId).executeUpdate();
			}

			if (userId != null) {
				entityManager.createNativeQuery("""
						DELETE FROM app_user
						 WHERE user_id = :userId
						""").setParameter("userId", userId).executeUpdate();
			}

			entityManager.clear();
		});
	}

	@Test
	void Redis_Stream_요청이_Lua_Outbox_Kafka를_거쳐_DB에_저장된다() throws Exception {

		streamProducer.publish(couponId, userId, requestId);
		
		// 1. Redis Stream Consumer가 메시지를 가져가 Lua를 실행했는지 확인
		awaitUntil(
		    "Lua가 실행되지 않았습니다.",
		    () -> String.valueOf(INITIAL_STOCK - 1).equals(
		        redisTemplate.opsForValue().get(stockKey())
		    )
		);

		// 2. Lua 성공 후 Outbox가 저장됐는지 확인
		awaitUntil(
		    "Outbox가 저장되지 않았습니다.",
		    () -> issueMessageRepository.findAll()
		        .stream()
		        .anyMatch(message ->
		            message.getMessageKey().equals(requestId)
		        )
		);

		// 3. Outbox가 Kafka로 발행됐는지 확인
		awaitUntil(
		    "Outbox가 SENT 상태로 변경되지 않았습니다.",
		    () -> issueMessageRepository.findAll()
		        .stream()
		        .anyMatch(message ->
		            message.getMessageKey().equals(requestId) && message.getStatus() == IssueMessageStatus.SENT
		        )
		);

		// 4. Kafka Consumer가 이벤트를 받아 최종 발급 내역을 저장했는지 확인
		awaitUntil(
		    "Kafka Consumer가 쿠폰 발급 내역을 DB에 저장하지 못했습니다.",
		    () -> couponIssueRepository.existsByRequestId(requestId)
		);
		
		IssueMessage outbox = issueMessageRepository.findAll().stream()
				.filter(message -> message.getTopic().equals(KafkaTopics.COUPON_ISSUE_EVENT))
				.filter(message -> message.getMessageKey().equals(requestId)).findFirst().orElseThrow();

		CouponIssue couponIssue = couponIssueRepository.findAll().stream()
				.filter(issue -> issue.getRequestId().equals(requestId)).findFirst().orElseThrow();

		CouponStock couponStock = couponStockRepository.findById(couponId).orElseThrow();

		// markConsumed()가 coupon_issue insert와 같은 트랜잭션에서 커밋되므로(CouponIssuePersister),
		// 4번 대기 조건(existsByRequestId)이 통과한 시점엔 이미 SENT가 아니라 CONSUMED로 확정돼 있다.
		assertThat(outbox.getStatus()).isEqualTo(IssueMessageStatus.CONSUMED);

		assertThat(outbox.getRetryCount()).isZero();

		assertThat(outbox.getProcessedAt()).isNotNull();

		assertThat(outbox.getSequenceNo()).isEqualTo(1L);

		assertThat(couponIssue.getCouponIssueId()).isNotNull();

		assertThat(couponIssue.getSequenceNo()).isEqualTo(1L);

		assertThat(couponIssue.getRequestId()).isEqualTo(requestId);

		assertThat(couponIssue.getCouponCode()).hasSize(32);

		assertThat(couponIssue.getExpiresAt()).isNotNull();

		assertThat(couponStock.getIssuedQuantity()).isEqualTo(1);

		assertThat(couponStock.getRemainingQuantity()).isEqualTo(INITIAL_STOCK - 1);

		assertThat(redisTemplate.opsForValue().get(stockKey())).isEqualTo(String.valueOf(INITIAL_STOCK - 1));

		assertThat(redisTemplate.opsForValue().get(sequenceKey())).isEqualTo("1");

		assertThat(redisTemplate.opsForHash().get(applicantsKey(), userId.toString())).isEqualTo(requestId);

		assertThat(redisTemplate.opsForHash().get(requestSequenceKey(), requestId)).isEqualTo("1");
	}

	private void awaitUntil(String failureMessage, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;

		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}

			Thread.sleep(POLL_INTERVAL_MILLIS);
		}

		fail(failureMessage + System.lineSeparator() + pipelineState());
	}

	private String stockKey() {
		return "coupon:issue:stock:{" + couponId + "}";
	}

	private String applicantsKey() {
		return "coupon:issue:applicants:{" + couponId + "}";
	}

	private String sequenceKey() {
		return "coupon:issue:sequence:{" + couponId + "}";
	}

	private String requestSequenceKey() {
		return "coupon:issue:request-sequence:{" + couponId + "}";
	}
	
	private String pipelineState() {
	    String redisStock = redisTemplate.opsForValue().get(stockKey());

	    String outboxState = issueMessageRepository.findAll()
	    	.stream()
	    	.filter(message -> message.getMessageKey().equals(requestId))
	    	.map(message ->
	    		"status = " + message.getStatus() + ", retryCount = " + message.getRetryCount() + ", lastError = " + message.getLastError()
	    	)
	    	.findFirst()
	    	.orElse("Outbox 없음");

	    boolean couponIssueExists =couponIssueRepository.existsByRequestId(requestId);

	    return "Redis stock = " + redisStock + ", Outbox = " + outboxState + ", CouponIssue exists = " + couponIssueExists;
	}
}