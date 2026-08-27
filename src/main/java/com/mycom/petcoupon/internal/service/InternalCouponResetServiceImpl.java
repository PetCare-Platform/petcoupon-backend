package com.mycom.petcoupon.internal.service;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
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

	@PersistenceContext
	private EntityManager entityManager;

	private final CouponStockRepository couponStockRepository;
	private final CouponIssueLuaService couponIssueLuaService;
	private final StringRedisTemplate redisTemplate;
	private final CouponIssueStreamProperties streamProperties;

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
	 * <p>초기화는 DB와 Redis 발급 상태만 되돌린다. Redis Stream 또는 Outbox에
	 * 앞 회차 메시지가 남아 있으면 초기화 이후 뒤늦게 처리되어 새 회차 재고를
	 * 차감할 수 있으므로 초기화를 진행하면 안 된다.
	 *
	 * <table border="1">
	 *   <caption>초기화를 차단하는 잔여 메시지</caption>
	 *   <tr><th>상태</th><th>다음 회차에</th><th>결과</th><th>처리</th></tr>
	 *   <tr><td>Stream 미배달</td><td>Consumer가 읽어감</td><td>유령 발급 가능</td><td><b>거절</b></td></tr>
	 *   <tr><td>Stream Pending</td><td>회수 스케줄러가 재처리</td><td>유령 발급 가능</td><td><b>거절</b></td></tr>
	 *   <tr><td>Outbox 미발행</td><td>Publisher가 Kafka로 발행</td><td>유령 발급 가능</td><td><b>거절</b></td></tr>
	 * </table>
	 *
	 * <p>Consumer Group이 없지만 Stream 메시지가 존재하는 경우에도 초기화를 거절한다.
	 * Group이 복구되면서 {@code 0-0}부터 기존 메시지가 다시 전달될 수 있기 때문이다.
	 *
	 * <p>Stream은 쿠폰별로 나뉘지 않은 전역 Stream이므로 다른 쿠폰의 메시지가
	 * 남아 있더라도 환경이 완전히 비워지지 않은 것으로 보고 초기화를 거절한다.
	 *
	 * <p>검사 자체가 실패해도 초기화를 거절한다. Redis에 접근할 수 없다는 것은
	 * 메시지가 없다는 뜻이 아니라 잔여 여부를 확인할 수 없다는 뜻이다.
	 * 강제로 초기화해야 하는 경우에만 {@code force=true}를 사용한다.
	 */
	private void validatePipelineDrained(Long couponId) {
		long outboxUnpublished = countUnpublishedMessages(couponId);
		long streamUndelivered = 0L;
		long streamPending = 0L;

		try {
			StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
			String streamKey = streamProperties.getKey();

			// 스트림이 아직 안 만들어졌으면 남은 메시지도 없다.
			if (Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
				// XINFO GROUPS 는 미배달 건수를 직접 주지 않는다. 마지막으로 쌓인 ID 와
				// 그룹이 마지막으로 배달한 ID 가 다르면 아직 아무도 안 가져간 신청이 있다는 뜻이다.
				String lastGeneratedId = streamOps.info(streamKey).lastGeneratedId();

				StreamInfo.XInfoGroup issueGroup = streamOps.groups(streamKey).stream()
						.filter(group -> group.groupName().equals(streamProperties.getGroup()))
						.findFirst()
						.orElse(null);
				
				if (issueGroup == null) {
					/*
					 * Stream은 있는데 Consumer Group이 없다면,
					 * Group 복구 시 0-0부터 기존 메시지가 다시 전달될 수 있다.
					 */
					Long streamSize = streamOps.size(streamKey);
					
					if (streamSize != null && streamSize > 0) {
						streamUndelivered = 1L;
					}
				} else {
					/*
					 * 마지막 생성 ID와 Group의 마지막 배달 ID가 다르면
					 * 아직 Consumer에게 전달되지 않은 메시지가 존재한다.
					 */
					if (!Objects.equals(issueGroup.lastDeliveredId(), lastGeneratedId)) {
						streamUndelivered = 1L;
					}
					
					// Pending Recovery Scheduler가 이 메시지를 다시 처리할 수 있으므로 Pending도 초기화 차단 대상에 포함한다.
					Long pendingCount = issueGroup.pendingCount();
					
					if(pendingCount != null) {
						streamPending = pendingCount;
					}
				}
			}
		} catch (DataAccessException e) {
			// 검사에 실패했다는 건 "남은 게 없다"가 아니라 "남았는지 모른다"는 뜻이다.
			// 모르는 채로 지우면 지난 회차 신청이 뒤늦게 확정되며 이번 회차 재고를 깎는다.
			// 확인할 수 없을 때는 진행하지 않는다. 정말 강행해야 하면 force 로 넘긴다.
			log.error("[Reset] 파이프라인 잔여 검사에 실패해 초기화를 중단한다. couponId={}", couponId, e);
			throw new GeneralException(CouponErrorCode.RESET_PRECONDITION_NOT_MET);
		}

		if (outboxUnpublished == 0 && streamUndelivered == 0 && streamPending == 0) {
			return;
		}

		log.warn(
				"[Reset] 앞 회차 메시지가 남아 초기화를 거절한다. couponId={} Stream미배달={} StreamPending={} Outbox미발행={}",
				couponId, streamUndelivered, streamPending, outboxUnpublished
		);
		throw new GeneralException(CouponErrorCode.RESET_PRECONDITION_NOT_MET);
	}

	/** Outbox 에 아직 Kafka 로 안 나간 건. poller 가 다음 주기에 집어 간다. */
	private long countUnpublishedMessages(Long couponId) {
		return entityManager.createQuery("""
						SELECT COUNT(m) FROM IssueMessage m
						 WHERE m.coupon.couponId = :couponId
						   AND m.status IN (
						       com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.PENDING,
						       com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.FAILED)
						""", Long.class)
				.setParameter("couponId", couponId)
				.getSingleResult();
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
	 * <p>키가 없으면 {@code null} 을 반환한다. 응답 필드가 {@code Integer} 인 이유이며,
	 * {@code null} 은 <b>초기화가 끝나지 않았다</b>는 뜻이다. 값이 숫자가 아닌 경우는
	 * 미완료가 아니라 상태 오염이므로 Lua 서비스가 예외로 올린다.
	 *
	 * <p>재고 키를 직접 만지지 않고 Lua 서비스에 맡긴다. 키 포맷의 원본이 거기에 있어서,
	 * 여기서 문자열을 다시 조립하면 규칙이 바뀔 때 두 곳을 고쳐야 한다.
	 *
	 * <p>Redis 는 DB 트랜잭션에 참여하지 않는다. 여기서 실패하면 DB 는 롤백되지만
	 * Redis 는 이미 지워진 상태로 남을 수 있으므로, 그 경우 초기화 API 를 다시 호출하면 된다.
	 * 부하 테스트 전용 API 라 재실행으로 복구되는 것으로 충분하다고 보고 별도 보상은 두지 않았다.
	 */
	private Integer resetIssueState(Long couponId, int totalQuantity) {
		return couponIssueLuaService.resetIssueState(couponId, totalQuantity);
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
