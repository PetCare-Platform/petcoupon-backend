package com.mycom.petcoupon.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponIssueHistory;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.HistoryActorType;
import com.mycom.petcoupon.coupon.entity.enums.IssueHistoryStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.config.CouponIssueLuaConfig;
import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaServiceImpl;
import com.mycom.petcoupon.coupon.issue.service.CouponIssuePipelineDrainCheckerImpl;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.internal.dto.req.CouponResetRequest;
import com.mycom.petcoupon.internal.dto.res.CouponResetResponse;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 실제 DB 로 검증한다.
 *
 * <p>EntityManager 로 직접 만든 JPQL 은 애플리케이션 기동 시점에 검증되지 않고 실행할 때
 * 터진다. 목으로 대체하면 오타를 잡지 못하므로, 쿼리가 실제로 실행되게 둔다.
 * 삭제 순서(외래키 제약)도 진짜 DB 에서만 검증된다.
 *
 * <p>실행 전 MySQL 이 떠 있어야 한다: {@code docker compose up -d mysql}
 * 각 테스트는 트랜잭션 안에서 돌고 끝나면 롤백되므로 데이터가 남지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
		InternalCouponResetServiceImpl.class,
		CouponIssueLuaServiceImpl.class,
		CouponIssueLuaConfig.class,
		CouponIssuePipelineDrainCheckerImpl.class
})
// Redis 정리까지 실제로 확인해야 해서 JPA 슬라이스에 Redis 자동설정을 더한다.
// 목으로 두면 키가 진짜 지워졌는지 알 수 없다. 실행 전 Redis 가 떠 있어야 한다.
@ImportAutoConfiguration(DataRedisAutoConfiguration.class)
// 잔여 메시지 검사가 Stream 키·그룹 이름을 알아야 한다. 슬라이스라 애플리케이션 클래스의
// @EnableConfigurationProperties 가 적용되지 않으므로 여기서 다시 켠다.
@EnableConfigurationProperties(CouponIssueStreamProperties.class)
@TestPropertySource(properties = {
	"coupon.issue.stream.key=coupon:issue:stream:reset-test",
	"coupon.issue.stream.group=coupon-issue-reset-test-group",
	"coupon.issue.stream.consumer=reset-test-consumer"
})
class InternalCouponResetServiceImplTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private InternalCouponResetServiceImpl internalCouponResetService;

	@Autowired
	private StringRedisTemplate redisTemplate;
	
	@Autowired
	private CouponIssueStreamProperties streamProperties;

	private Coupon coupon;
	private AppUser user;

	@BeforeEach
	void setUp() {
		user = AppUser.builder()
				.name("테스트회원")
				.email("reset-test@test.com")
				.phone("010-1234-5678")
				.role(UserRole.ROLE_MEMBER)
				.build();
		entityManager.persist(user);

		Event event = Event.builder()
				.createdBy(user)
				.name("초기화 테스트 이벤트")
				.description("reset")
				.openAt(LocalDateTime.of(2026, 8, 20, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
				.build();
		entityManager.persist(event);

		coupon = Coupon.builder()
				.event(event)
				.name("초기화 테스트 쿠폰")
				.discountType(DiscountType.FIXED_AMOUNT)
				.discountValue(5_000)
				.minOrderAmount(10_000)
				.issueStartAt(LocalDateTime.of(2026, 8, 20, 9, 0))
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
	
	@AfterEach
	void tearDownRedisStream() {
		redisTemplate.delete(streamProperties.getKey());
	}

	@Test
	@DisplayName("발급 데이터를 모두 지우고 재고를 총재고 기준으로 되돌린다")
	void resetDeletesIssueDataAndRestoresStock() {
		발급데이터를_만든다(3);

		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null, null));

		assertEquals(3, response.deletedIssues());
		assertEquals(3, response.deletedHistories());
		assertEquals(3, response.deletedIdempotencyKeys());
		assertEquals(100, response.totalQuantity());
		assertEquals(100, response.remainingQuantity());

		assertEquals(0L, 남은개수("SELECT COUNT(c) FROM CouponIssue c WHERE c.coupon.couponId = :couponId"));
		assertEquals(0L, 남은개수("SELECT COUNT(h) FROM CouponIssueHistory h WHERE h.couponId = :couponId"));
		assertEquals(0L, 남은개수("SELECT COUNT(k) FROM IdempotencyKey k WHERE k.coupon.couponId = :couponId"));

		CouponStock stock = entityManager.find(CouponStock.class, coupon.getCouponId());
		assertEquals(0, stock.getIssuedQuantity());
		assertEquals(100, stock.getRemainingQuantity());
	}

	@Test
	@DisplayName("알림 로그가 있어도 외래키 위반 없이 삭제된다")
	void resetDeletesNotificationLogsBeforeIssues() {
		발급데이터를_만든다(1);
		Long couponIssueId = 첫_발급_id();

		// NotificationLog 는 빌더가 없어 네이티브 INSERT 로 만든다.
		// 알림 발송 기능이 붙었을 때 리셋이 외래키 위반으로 실패하지 않는지 확인하는 것이 목적이다.
		entityManager.createNativeQuery("""
						INSERT INTO notification_log
						       (coupon_issue_id, user_id, channel, recipient_masked, status, created_at)
						VALUES (:couponIssueId, :userId, 'SMS', '010-****-1234', 'SENT', NOW(6))
						""")
				.setParameter("couponIssueId", couponIssueId)
				.setParameter("userId", user.getUserId())
				.executeUpdate();

		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null, null));

		assertEquals(1, response.deletedNotifications());
		assertEquals(1, response.deletedIssues());
		assertEquals(0L, 남은개수(
				"SELECT COUNT(n) FROM NotificationLog n WHERE n.couponIssue.coupon.couponId = :couponId"));
	}

	@Test
	@DisplayName("발급 메시지도 함께 삭제된다 - 다음 회차에서 순번이 겹치지 않도록")
	void resetDeletesIssueMessages() {
		// IssueMessage 는 빌더가 없어 네이티브 INSERT 로 만든다.
		메시지를_만든다(2);

		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null, null));

		assertEquals(2, response.deletedMessages());
		assertEquals(0L, 남은개수(
				"SELECT COUNT(m) FROM IssueMessage m WHERE m.coupon.couponId = :couponId"));
	}

	@Test
	@DisplayName("정합성 검증 결과도 함께 삭제된다 - 상세가 있어도 외래키 위반 없이")
	void resetDeletesReconciliationReports() {
		정합성_리포트를_만든다();

		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null, null));

		assertEquals(1, response.deletedReports());
		assertEquals(0L, 남은개수(
				"SELECT COUNT(r) FROM ReconciliationReport r WHERE r.coupon.couponId = :couponId"));
		assertEquals(0L, 남은개수(
				"SELECT COUNT(d) FROM VerificationDetail d WHERE d.report.coupon.couponId = :couponId"));
	}

	@Test
	@DisplayName("재고를 되돌리면 updatedAt 도 갱신된다")
	void resetUpdatesStockTimestamp() {
		LocalDateTime before = entityManager.find(CouponStock.class, coupon.getCouponId()).getUpdatedAt();

		internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(200, null));

		LocalDateTime after = entityManager.find(CouponStock.class, coupon.getCouponId()).getUpdatedAt();
		assertNotNull(after);
		assertTrue(!after.isBefore(before), "재고 변경 시각이 갱신돼야 한다");
	}

	@Test
	@DisplayName("totalQuantity 를 주면 총재고까지 바꾼다")
	void resetChangesTotalQuantityWhenGiven() {
		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(10_000, null));

		assertEquals(10_000, response.totalQuantity());
		assertEquals(10_000, response.remainingQuantity());

		CouponStock stock = entityManager.find(CouponStock.class, coupon.getCouponId());
		assertEquals(10_000, stock.getTotalQuantity());
		assertEquals(0, stock.getIssuedQuantity());
		assertEquals(10_000, stock.getRemainingQuantity());
	}

	@Test
	@DisplayName("발급 데이터가 없어도 실패하지 않는다 - 매 회차 반복 호출된다")
	void resetSucceedsWhenNothingToDelete() {
		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null, null));

		assertEquals(0, response.deletedIssues());
		assertEquals(0, response.deletedHistories());
		assertEquals(0, response.deletedIdempotencyKeys());
		assertEquals(0, response.deletedNotifications());
		assertEquals(0, response.deletedMessages());
	}

	@Test
	@DisplayName("Redis 발급 상태를 지우고 재고를 다시 넣는다 - 이게 없으면 2회차가 전부 ALREADY_APPLIED 로 튕긴다")
	void resetClearsRedisIssueStateAndRestoresStock() {
		Long couponId = coupon.getCouponId();

		// 1회차를 돈 뒤의 Redis 상태를 흉내낸다 - 재고는 소진, 신청자와 순번은 쌓여 있다.
		redisTemplate.opsForValue().set(issueKey("stock", couponId), "0");
		redisTemplate.opsForHash().put(issueKey("applicants", couponId), "1", "req-1");
		redisTemplate.opsForValue().set(issueKey("sequence", couponId), "10000");
		redisTemplate.opsForHash().put(issueKey("request-sequence", couponId), "req-1", "1");

		try {
			CouponResetResponse response =
					internalCouponResetService.reset(couponId, new CouponResetRequest(500, null));

			// 이전 회차 흔적이 남아 있으면 같은 유저가 다시 신청할 때 ALREADY_APPLIED 가 된다.
			assertFalse(redisTemplate.hasKey(issueKey("applicants", couponId)));
			assertFalse(redisTemplate.hasKey(issueKey("sequence", couponId)));
			assertFalse(redisTemplate.hasKey(issueKey("request-sequence", couponId)));

			// 재고 키는 지워진 채로 두면 Lua 가 STOCK_NOT_INITIALIZED 를 반환한다. 다시 채워져 있어야 한다.
			String storedStock = redisTemplate.opsForValue().get(issueKey("stock", couponId));
			assertEquals("500", storedStock);

			// redisStock 은 요청값을 되돌려준 값이 아니라 Redis 에서 다시 읽은 값이어야 한다.
			// 그래서 요청한 500 이 아니라 "실제로 저장된 값"과 비교한다.
			assertNotNull(response.redisStock(), "Redis 재고를 읽지 못하면 초기화가 끝난 것으로 볼 수 없다");
			assertEquals(Integer.valueOf(storedStock), response.redisStock());
		} finally {
			redisTemplate.delete(java.util.List.of(
					issueKey("stock", couponId),
					issueKey("applicants", couponId),
					issueKey("sequence", couponId),
					issueKey("request-sequence", couponId)
			));
		}
	}

	private String issueKey(String suffix, Long couponId) {
		return "coupon:issue:" + suffix + ":{" + couponId + "}";
	}

	@Test
	@DisplayName("존재하지 않는 쿠폰이면 COUPON_NOT_FOUND")
	void resetThrowsWhenCouponDoesNotExist() {
		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> internalCouponResetService.reset(999_999L, new CouponResetRequest(null, null))
		);

		assertEquals(CouponErrorCode.COUPON_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	@DisplayName("Outbox 에 아직 발행 안 된 메시지가 있으면 초기화를 거절한다 - 지우면 그 메시지가 다음 회차 재고를 깎는다")
	void resetRejectedWhenOutboxHasUnpublishedMessages() {
		미발행_메시지를_만든다();

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null, null))
		);

		assertEquals(CouponErrorCode.RESET_PRECONDITION_NOT_MET, exception.getErrorCode());

		// 거절했으면 아무것도 지우지 않아야 한다. 반쯤 지우고 실패하면 상태가 더 나빠진다.
		assertEquals(1L, 남은개수("SELECT COUNT(m) FROM IssueMessage m WHERE m.coupon.couponId = :couponId"));
	}

	@Test
	@DisplayName("Redis Stream Pending 메시지가 있으면 초기화를 거절한다 - 회수 스케줄러가 뒤늦게 처리할 수 있다")
	void resetRejectedWhenStreamHasPendingMessages() {
		Pending_메시지를_만든다();

		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> internalCouponResetService.reset(
						coupon.getCouponId(),
						new CouponResetRequest(null, null)
				)
		);

		assertEquals(
				CouponErrorCode.RESET_PRECONDITION_NOT_MET,
				exception.getErrorCode()
		);

		Long pendingCount = redisTemplate.opsForStream()
				.pending(
						streamProperties.getKey(),
						streamProperties.getGroup()
				)
				.getTotalPendingMessages();

		// 초기화를 거절했으므로 Pending 메시지도 그대로 남아 있어야 한다.
		assertEquals(1L, pendingCount);
	}
	
	@Test
	@DisplayName("Stream 메시지는 있지만 Consumer Group이 없으면 초기화를 거절한다")
	void resetRejectedWhenStreamExistsWithoutConsumerGroup() {
		String streamKey = streamProperties.getKey();

		redisTemplate.opsForStream().add(
			MapRecord.create(
				streamKey,
				Map.of("requestId", "group-missing-request","couponId", coupon.getCouponId().toString(),"userId", user.getUserId().toString())
			)
		);

		GeneralException exception = assertThrows(
			GeneralException.class,
			() -> internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null, null))
		);

		assertEquals(CouponErrorCode.RESET_PRECONDITION_NOT_MET, exception.getErrorCode());

		assertTrue(redisTemplate.hasKey(streamKey));
	}
	
	@Test
	@DisplayName("force 를 주면 미발행 메시지가 있어도 초기화한다 - 되찾을 수 없는 잔여물을 사람이 넘길 때")
	void resetProceedsWhenForced() {
		미발행_메시지를_만든다();

		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null, true));

		assertEquals(1, response.deletedMessages());
		assertEquals(0L, 남은개수("SELECT COUNT(m) FROM IssueMessage m WHERE m.coupon.couponId = :couponId"));
	}

	/** poller 가 아직 집어가지 않은 Outbox 한 건. 다른 테스트의 메시지는 CONSUMED 라 검사에 걸리지 않는다. */
	private void 미발행_메시지를_만든다() {
		entityManager.createNativeQuery("""
						INSERT INTO issue_message
						       (coupon_id, user_id, sequence_no, message_key, topic, payload,
						        status, retry_count, created_at)
						VALUES (:couponId, :userId, 9001, :messageKey, 'coupon-issue', '{}',
						        'PENDING', 0, NOW(6))
						""")
				.setParameter("couponId", coupon.getCouponId())
				.setParameter("userId", user.getUserId())
				.setParameter("messageKey", "pending-" + coupon.getCouponId())
				.executeUpdate();
		entityManager.flush();
	}

	private void 발급데이터를_만든다(int count) {
		// uk_issue_coupon_user(1인 1매) 때문에 발급 건마다 사용자가 달라야 한다.
		for (int i = 1; i <= count; i++) {
			AppUser issuedUser = AppUser.builder()
					.name("테스트회원" + i)
					.email("reset-test-" + i + "@test.com")
					.phone("010-1234-" + String.format("%04d", i))
					.role(UserRole.ROLE_MEMBER)
					.build();
			entityManager.persist(issuedUser);

			CouponIssue issue = CouponIssue.builder()
					.coupon(coupon)
					.user(issuedUser)
					.sequenceNo(i)
					.couponCode("RESET-TEST-CODE-" + i)
					.requestId("reset-test-request-" + i)
					.expiresAt(LocalDateTime.of(2026, 9, 30, 23, 59))
					.build();
			entityManager.persist(issue);

			entityManager.persist(CouponIssueHistory.builder()
					.couponIssue(issue)
					.couponId(coupon.getCouponId())
					.userId(issuedUser.getUserId())
					.fromStatus(IssueHistoryStatus.NONE)
					.toStatus(IssueHistoryStatus.ISSUED)
					.actorType(HistoryActorType.SYSTEM)
					.build());

			entityManager.persist(IdempotencyKey.builder()
					.user(issuedUser)
					.coupon(coupon)
					.idempotencyKey("reset-test-key-" + i)
					.requestHash("hash-" + i)
					.expiresAt(LocalDateTime.of(2026, 9, 30, 23, 59))
					.build());
		}
		entityManager.flush();
	}

	private void 메시지를_만든다(int count) {
		for (int i = 1; i <= count; i++) {
			entityManager.createNativeQuery("""
							INSERT INTO issue_message
							       (coupon_id, user_id, sequence_no, message_key, topic, payload,
							        status, retry_count, created_at)
							VALUES (:couponId, :userId, :seq, :messageKey, 'coupon-issue', '{}',
							        'CONSUMED', 0, NOW(6))
							""")
					.setParameter("couponId", coupon.getCouponId())
					.setParameter("userId", user.getUserId())
					.setParameter("seq", i)
					.setParameter("messageKey", coupon.getCouponId() + ":" + i)
					.executeUpdate();
		}
	}

	private void 정합성_리포트를_만든다() {
		entityManager.createNativeQuery("""
						INSERT INTO reconciliation_report
						       (coupon_id, as_of_at, started_at, finished_at,
						        total_count, success_count, error_count, result,
						        stock_total, stock_issued, stock_remaining,
						        db_active_count, db_expired_count, db_dlq_count)
						VALUES (:couponId, NOW(6), NOW(6), NOW(6),
						        10, 10, 0, 'MATCHED',
						        100, 10, 90,
						        10, 0, 0)
						""")
				.setParameter("couponId", coupon.getCouponId())
				.executeUpdate();

		Long reportId = ((Number) entityManager.createNativeQuery(
						"SELECT MAX(report_id) FROM reconciliation_report WHERE coupon_id = :couponId")
				.setParameter("couponId", coupon.getCouponId())
				.getSingleResult()).longValue();

		entityManager.createNativeQuery("""
						INSERT INTO verification_detail (report_id, error_type, created_at)
						VALUES (:reportId, 'STOCK_MISMATCH', NOW(6))
						""")
				.setParameter("reportId", reportId)
				.executeUpdate();
	}

	private Long 첫_발급_id() {
		return entityManager.createQuery(
						"SELECT MIN(c.couponIssueId) FROM CouponIssue c WHERE c.coupon.couponId = :couponId", Long.class)
				.setParameter("couponId", coupon.getCouponId())
				.getSingleResult();
	}

	private long 남은개수(String jpql) {
		return entityManager.createQuery(jpql, Long.class)
				.setParameter("couponId", coupon.getCouponId())
				.getSingleResult();
	}
	
	private void Pending_메시지를_만든다() {
		String streamKey = streamProperties.getKey();
		String group = streamProperties.getGroup();

		StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();

		MapRecord<String, String, String> record = MapRecord.create(
			streamKey,
			Map.of(	
				"requestId", "reset-pending-request",	
				"couponId", coupon.getCouponId().toString(),
				"userId", user.getUserId().toString()
			)
		);

		streamOps.add(record);

		// 0-0부터 읽도록 Group을 생성한다.
		streamOps.createGroup(streamKey, ReadOffset.from("0-0"), group);

		/*
		 * 죽은 Consumer가 메시지를 가져간 뒤 ACK하지 않은 상황을 만든다.
		 * XREADGROUP이 실행되면 메시지가 Pending Entries List에 들어간다.
		 */
		List<MapRecord<String, String, String>> delivered =
			streamOps.read(
				Consumer.from(group, "stopped-reset-test-consumer"),
				StreamReadOptions.empty().count(1),
				StreamOffset.create(streamKey, ReadOffset.lastConsumed())
			);

		assertNotNull(delivered);
		assertEquals(1, delivered.size());

		Long pendingCount = streamOps.pending(streamKey, group).getTotalPendingMessages();

		assertEquals(1L, pendingCount);
	}
}
