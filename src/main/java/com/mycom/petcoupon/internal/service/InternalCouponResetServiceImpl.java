package com.mycom.petcoupon.internal.service;

import java.time.LocalDateTime;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.coupon.issue.service.CouponIssuePipelineDrainChecker;
import com.mycom.petcoupon.coupon.issue.service.PipelineDrainStatus;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.internal.dto.req.CouponResetRequest;
import com.mycom.petcoupon.internal.dto.res.CouponResetResponse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부하 테스트 데이터 초기화.
 *
 * <p>여러 도메인의 테이블을 정해진 순서대로 지워야 하는 작업이라, 각 도메인 리포지토리에
 * 삭제 메서드를 추가하는 대신 {@link EntityManager} 로 한곳에서 처리한다.
 * 삭제 순서가 한 파일에 모여 있어야 읽히고, 테스트 전용 코드가 프로덕션 리포지토리의
 * 공개 메서드로 남지 않는다.
 */
@Slf4j
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
	private final CouponIssuePipelineDrainChecker pipelineDrainChecker;

	@Override
	@Transactional
	public CouponResetResponse reset(Long couponId, CouponResetRequest request) {
		CouponStock stock = couponStockRepository.findById(couponId)
				.orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

		// 지우기 전에 파이프라인이 비었는지 먼저 본다. 남은 채로 지우면 그 메시지가
		// 뒤늦게 처리되면서 이번 회차 재고를 깎는다.
		if (!request.isForced()) {
			validatePipelineDrained(couponId);
		}

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
		Integer redisStock = resetIssueState(couponId, totalQuantity);

		// 응답은 호출자만 보고 사라진다. 회차가 끝난 뒤 "그때 뭘 지웠는지" 되짚을 수 있게 로그로도 남긴다.
		log.info(
				"[Reset] couponId={} 발급={} 이력={} 멱등키={} 알림={} Outbox={} 검증리포트={} 총재고={} redis재고={}",
				couponId, deletedIssues, deletedHistories, deletedIdempotencyKeys,
				deletedNotifications, deletedMessages, deletedReports, totalQuantity, redisStock
		);

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
				.redisStock(redisStock)
				.build();
	}

	/**
	 * 앞 회차 신청이 파이프라인에 남아 있지 않은지 확인한다. 남았으면 409 로 거절한다.
	 *
	 * <p>초기화는 DB 와 Redis 만 되돌린다. Redis Stream 과 Kafka 에 떠 있는 메시지는 지우지 못하고,
	 * 특히 Outbox 발행은 {@code kafkaTemplate.send()} 가 DB 트랜잭션 밖에서 일어나 이미 나간 것을
	 * 되돌릴 방법이 없다. 그 상태로 지우면 지난 회차 신청이 뒤늦게 확정되면서 이번 회차 재고를 깎는데,
	 * {@code coupon_issue} 를 모두 지운 뒤라 유니크 제약도 이를 막지 못한다.
	 *
	 * <p>실제 잔여 판단 기준(어떤 상태를 왜 막는지)은 {@link CouponIssuePipelineDrainChecker} 참고.
	 * 여기서는 그 결과(검사 실패 포함)를 받아 무조건 거절만 한다 — 강행이 필요하면 {@code force} 로 넘긴다.
	 *
	 * <p><b>알려진 한계 — 체크와 리셋 사이에 락이 없다.</b> 여기서 드레인 여부를 확인한 시점과
	 * 실제 DELETE 가 실행되는 시점 사이에는 아무 락도 없다. 그 사이에 새 발급 요청이 들어오면
	 * 지워지는 중인(또는 막 지워진) 데이터에 반영되거나, 리셋 직후의 새 회차 재고를 이전 요청이
	 * 갉아먹을 수 있다. 완전히 막으려면 발급 API({@code CouponController.issue()} 또는 그 안의
	 * Lua) 자체가 "정비 중" 락을 확인하도록 발급 hot path 를 건드려야 하는데, 이 초기화 API 하나의
	 * 스코프를 넘는 변경이라 여기서는 하지 않는다.
	 *
	 * <p>고치지 않는 이유 — 이 API 는 부하테스트 전용({@code /internal/...}) 이고 통제된 세션에서
	 * 전용 테스트 쿠폰({@code SEED-*})에만 쓴다. 실제 발급 트래픽과 같은 쿠폰에 동시에 겹칠 상황이
	 * 실무에서 거의 없고, 겹치더라도 영향 범위가 그 테스트 회차 데이터로 한정돼 프로덕션 데이터엔
	 * 닿지 않는다.
	 */
	private void validatePipelineDrained(Long couponId) {
		PipelineDrainStatus status = pipelineDrainChecker.check(couponId);

		if (!status.isBlocked()) {
			return;
		}

		log.warn(
				"[Reset] 앞 회차 메시지가 남아 초기화를 거절한다. couponId={} Stream미배달={} Outbox미발행={} 검사실패={}",
				couponId, status.streamUndelivered(), status.outboxUnpublished(), status.checkFailed()
		);
		throw new GeneralException(CouponErrorCode.RESET_PRECONDITION_NOT_MET);
	}

	/**
	 * Redis 발급 상태를 초기화하고, <b>기록된 재고를 다시 읽어서</b> 반환한다.
	 *
	 * <p>{@code clearIssueState} 는 신청자·순번 키와 함께 <b>재고 키까지 지운다.</b>
	 * Lua 는 재고 키가 없으면 {@code STOCK_NOT_INITIALIZED} 를 반환하므로,
	 * 지운 뒤 반드시 재고를 다시 넣어야 다음 회차 발급이 동작한다.
	 *
	 * <p>쓴 값을 그대로 돌려주면 Redis 반영 여부를 검증할 수 없다. 키 이름이 틀렸거나
	 * 다른 인스턴스가 곧바로 덮어써도 응답은 똑같이 정상으로 보인다. 그래서 {@code SET} 뒤에
	 * 다시 읽어서 실제로 저장된 값을 응답에 싣는다. 호출자는 이 값이 {@code totalQuantity} 와
	 * 같은지 확인해 초기화 성공 여부를 판정할 수 있다.
	 *
	 * <p>읽기에 실패하거나 값이 숫자가 아니면 {@code null} 을 반환한다. 응답 필드가
	 * {@code Integer} 인 이유이며, {@code null} 은 <b>초기화가 끝나지 않았다</b>는 뜻이다.
	 *
	 * <p>Redis 는 DB 트랜잭션에 참여하지 않는다. 여기서 실패하면 DB 는 롤백되지만
	 * Redis 는 이미 지워진 상태로 남을 수 있으므로, 그 경우 초기화 API 를 다시 호출하면 된다.
	 * 부하 테스트 전용 API 라 재실행으로 복구되는 것으로 충분하다고 보고 별도 보상은 두지 않았다.
	 */
	private Integer resetIssueState(Long couponId, int totalQuantity) {
		couponIssueLuaService.clearIssueState(couponId);

		String stockKey = String.format(STOCK_KEY_FORMAT, couponId);
		redisTemplate.opsForValue().set(stockKey, String.valueOf(totalQuantity));

		return readRedisStock(stockKey);
	}

	/**
	 * 재고 키를 읽어 숫자로 바꾼다. 키가 없거나 숫자가 아니면 {@code null}.
	 *
	 * <p>검증용 값이라 여기서 예외를 던지지 않는다. 초기화 자체는 이미 끝났고,
	 * 읽기에 실패했다는 사실은 {@code null} 로 호출자에게 그대로 전달하는 편이 낫다.
	 */
	private Integer readRedisStock(String stockKey) {
		String raw = redisTemplate.opsForValue().get(stockKey);

		if (raw == null) {
			return null;
		}

		try {
			return Integer.valueOf(raw);
		} catch (NumberFormatException e) {
			return null;
		}
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
