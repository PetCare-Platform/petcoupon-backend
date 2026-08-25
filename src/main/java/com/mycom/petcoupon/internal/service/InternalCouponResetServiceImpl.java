package com.mycom.petcoupon.internal.service;

import java.time.LocalDateTime;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.internal.dto.req.CouponResetRequest;
import com.mycom.petcoupon.internal.dto.res.CouponResetResponse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;

/**
 * 부하 테스트 데이터 초기화.
 *
 * <p>여러 도메인의 테이블을 정해진 순서대로 지워야 하는 작업이라, 각 도메인 리포지토리에
 * 삭제 메서드를 추가하는 대신 {@link EntityManager} 로 한곳에서 처리한다.
 * 삭제 순서가 한 파일에 모여 있어야 읽히고, 테스트 전용 코드가 프로덕션 리포지토리의
 * 공개 메서드로 남지 않는다.
 */
@Service
@RequiredArgsConstructor
public class InternalCouponResetServiceImpl implements InternalCouponResetService {

	/**
	 * Lua 가 재고를 읽고 쓰는 키. 형식의 원본은 {@code CouponIssueLuaServiceImpl.issueKey()} 인데
	 * private 이라 여기서 다시 쓴다. 재고 키를 설정하는 공개 메서드가 생기면 이 상수를 지우고
	 * 그 메서드를 호출하도록 바꾼다.
	 */
	private static final String STOCK_KEY_FORMAT = "coupon:issue:stock:{%d}";

	@PersistenceContext
	private EntityManager entityManager;

	private final CouponStockRepository couponStockRepository;
	private final CouponIssueLuaService couponIssueLuaService;
	private final StringRedisTemplate redisTemplate;

	@Override
	@Transactional
	public CouponResetResponse reset(Long couponId, CouponResetRequest request) {
		CouponStock stock = couponStockRepository.findById(couponId)
				.orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

		// 외래키 제약 때문에 순서가 중요하다.
		// coupon_issue 를 참조하는 세 테이블(이력, 멱등키, 알림 로그)을 먼저 지워야
		// coupon_issue 를 지울 수 있다.
		long deletedHistories = deleteHistories(couponId);
		long deletedIdempotencyKeys = deleteIdempotencyKeys(couponId);
		long deletedNotifications = deleteNotificationLogs(couponId);
		long deletedIssues = deleteIssues(couponId);

		// issue_message 는 coupon 만 참조하므로 순서와 무관하지만,
		// uk_message_sequence(coupon_id, sequence_no) 때문에 지우지 않으면
		// 다음 회차에서 순번이 겹쳐 전부 실패한다.
		long deletedMessages = deleteMessages(couponId);

		// 이전 회차의 정합성 검증 결과가 남아 있으면 대시보드가 헷갈린다.
		// verification_detail 의 외래키가 ON DELETE CASCADE 가 아니므로(ddl-auto 생성분)
		// 상세를 먼저 지우고 리포트를 지운다.
		long deletedReports = deleteReconciliationReports(couponId);

		int totalQuantity = request.totalQuantity() != null
				? request.totalQuantity()
				: stock.getTotalQuantity();

		resetStock(couponId, totalQuantity);

		// DB 를 비워도 Redis 에 이전 회차 기록이 남으면 다음 회차가 전부 ALREADY_APPLIED 로 튕긴다.
		resetIssueState(couponId, totalQuantity);

		return CouponResetResponse.builder()
				.couponId(couponId)
				.deletedHistories(deletedHistories)
				.deletedIdempotencyKeys(deletedIdempotencyKeys)
				.deletedNotifications(deletedNotifications)
				.deletedIssues(deletedIssues)
				.deletedMessages(deletedMessages)
				.deletedReports(deletedReports)
				.totalQuantity(totalQuantity)
				.remainingQuantity(totalQuantity)
				.redisStock(totalQuantity)
				.build();
	}

	/**
	 * Redis 발급 상태를 초기화한다.
	 *
	 * <p>{@code clearIssueState} 는 신청자·순번 키와 함께 <b>재고 키까지 지운다.</b>
	 * Lua 는 재고 키가 없으면 {@code STOCK_NOT_INITIALIZED} 를 반환하므로,
	 * 지운 뒤 반드시 재고를 다시 넣어야 다음 회차 발급이 동작한다.
	 *
	 * <p>Redis 는 DB 트랜잭션에 참여하지 않는다. 여기서 실패하면 DB 는 롤백되지만
	 * Redis 는 이미 지워진 상태로 남을 수 있으므로, 그 경우 초기화 API 를 다시 호출하면 된다.
	 * 부하 테스트 전용 API 라 재실행으로 복구되는 것으로 충분하다고 보고 별도 보상은 두지 않았다.
	 */
	private void resetIssueState(Long couponId, int totalQuantity) {
		couponIssueLuaService.clearIssueState(couponId);

		redisTemplate.opsForValue()
				.set(String.format(STOCK_KEY_FORMAT, couponId), String.valueOf(totalQuantity));
	}

	private long deleteHistories(Long couponId) {
		return entityManager.createQuery(
						"DELETE FROM CouponIssueHistory h WHERE h.couponId = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
	}

	private long deleteIdempotencyKeys(Long couponId) {
		return entityManager.createQuery(
						"DELETE FROM IdempotencyKey k WHERE k.coupon.couponId = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
	}

	/**
	 * 알림 로그는 coupon_id 를 직접 들고 있지 않으므로 coupon_issue 를 거쳐 찾는다.
	 * 현재는 알림 발송 기능이 없어 항상 0건이지만, 기능이 붙으면 이 삭제가 없을 때
	 * coupon_issue 삭제가 외래키 위반으로 실패한다.
	 */
	private long deleteNotificationLogs(Long couponId) {
		return entityManager.createQuery("""
						DELETE FROM NotificationLog n
						 WHERE n.couponIssue.couponIssueId IN (
						       SELECT c.couponIssueId FROM CouponIssue c WHERE c.coupon.couponId = :couponId)
						""")
				.setParameter("couponId", couponId)
				.executeUpdate();
	}

	private long deleteIssues(Long couponId) {
		return entityManager.createQuery(
						"DELETE FROM CouponIssue c WHERE c.coupon.couponId = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
	}

	private long deleteMessages(Long couponId) {
		return entityManager.createQuery(
						"DELETE FROM IssueMessage m WHERE m.coupon.couponId = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
	}

	/**
	 * 정합성 검증 결과를 지운다.
	 * verification_detail 의 외래키가 ON DELETE CASCADE 가 아니므로 상세를 먼저 지운다.
	 */
	private long deleteReconciliationReports(Long couponId) {
		entityManager.createQuery("""
						DELETE FROM VerificationDetail d
						 WHERE d.report.reportId IN (
						       SELECT r.reportId FROM ReconciliationReport r WHERE r.coupon.couponId = :couponId)
						""")
				.setParameter("couponId", couponId)
				.executeUpdate();

		return entityManager.createQuery(
						"DELETE FROM ReconciliationReport r WHERE r.coupon.couponId = :couponId")
				.setParameter("couponId", couponId)
				.executeUpdate();
	}

	/**
	 * 벌크 UPDATE 는 Hibernate 이벤트를 거치지 않아 {@code @LastModifiedDate} 가 동작하지 않는다.
	 * updatedAt 을 쿼리에서 직접 갱신한다.
	 */
	private void resetStock(Long couponId, int totalQuantity) {
		entityManager.createQuery("""
						UPDATE CouponStock s
						   SET s.totalQuantity = :totalQuantity,
						       s.issuedQuantity = 0,
						       s.remainingQuantity = :totalQuantity,
						       s.updatedAt = :now
						 WHERE s.couponId = :couponId
						""")
				.setParameter("totalQuantity", totalQuantity)
				.setParameter("now", LocalDateTime.now())
				.setParameter("couponId", couponId)
				.executeUpdate();

		// 벌크 연산은 영속성 컨텍스트를 거치지 않는다.
		// 위에서 조회해 둔 stock 엔티티가 낡은 값을 들고 있으므로 비운다.
		entityManager.clear();
	}
}
