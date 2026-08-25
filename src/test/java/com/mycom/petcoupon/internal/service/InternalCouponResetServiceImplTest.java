package com.mycom.petcoupon.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponIssueHistory;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.HistoryActorType;
import com.mycom.petcoupon.coupon.entity.enums.IssueHistoryStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
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
@Import(InternalCouponResetServiceImpl.class)
class InternalCouponResetServiceImplTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private InternalCouponResetServiceImpl internalCouponResetService;

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

	@Test
	@DisplayName("발급 데이터를 모두 지우고 재고를 총재고 기준으로 되돌린다")
	void resetDeletesIssueDataAndRestoresStock() {
		발급데이터를_만든다(3);

		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null));

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
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null));

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
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null));

		assertEquals(2, response.deletedMessages());
		assertEquals(0L, 남은개수(
				"SELECT COUNT(m) FROM IssueMessage m WHERE m.coupon.couponId = :couponId"));
	}

	@Test
	@DisplayName("정합성 검증 결과도 함께 삭제된다 - 상세가 있어도 외래키 위반 없이")
	void resetDeletesReconciliationReports() {
		정합성_리포트를_만든다();

		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null));

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

		internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(200));

		LocalDateTime after = entityManager.find(CouponStock.class, coupon.getCouponId()).getUpdatedAt();
		assertNotNull(after);
		assertTrue(!after.isBefore(before), "재고 변경 시각이 갱신돼야 한다");
	}

	@Test
	@DisplayName("totalQuantity 를 주면 총재고까지 바꾼다")
	void resetChangesTotalQuantityWhenGiven() {
		CouponResetResponse response =
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(10_000));

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
				internalCouponResetService.reset(coupon.getCouponId(), new CouponResetRequest(null));

		assertEquals(0, response.deletedIssues());
		assertEquals(0, response.deletedHistories());
		assertEquals(0, response.deletedIdempotencyKeys());
		assertEquals(0, response.deletedNotifications());
		assertEquals(0, response.deletedMessages());
	}

	@Test
	@DisplayName("존재하지 않는 쿠폰이면 COUPON_NOT_FOUND")
	void resetThrowsWhenCouponDoesNotExist() {
		GeneralException exception = assertThrows(
				GeneralException.class,
				() -> internalCouponResetService.reset(999_999L, new CouponResetRequest(null))
		);

		assertEquals(CouponErrorCode.COUPON_NOT_FOUND, exception.getErrorCode());
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
}
