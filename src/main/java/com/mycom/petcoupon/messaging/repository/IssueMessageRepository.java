package com.mycom.petcoupon.messaging.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueFailureReason;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;

public interface IssueMessageRepository extends JpaRepository<IssueMessage, Long> {

	// 커스텀 @Modifying 쿼리는 실제 구현 클래스(SimpleJpaRepository 같은)가 없어서
	// enableDefaultTransactions만으로는 트랜잭션이 안 열림 — 호출부(Kafka I/O/Consumer 스레드)에
	// @Transactional이 없을 수 있으므로 메서드에 직접 붙여줌
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE IssueMessage im SET im.status = :status WHERE im.messageId = :messageId")
	int updateStatus(@Param("messageId") Long messageId, @Param("status") IssueMessageStatus status);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE IssueMessage im SET im.status = :status, im.lastError = :lastError WHERE im.messageId = :messageId")
	int updateStatusWithError(
			@Param("messageId") Long messageId,
			@Param("status") IssueMessageStatus status,
			@Param("lastError") String lastError
	);

	// Recoverer에서 SELECT 후 별도 UPDATE하던 걸 topic+message_key(uk_message_key_topic) 기반
	// 단일 UPDATE로 합침 — 대상이 없으면 반환값 0으로 호출부가 판단
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE IssueMessage im
			   SET im.status = :status, im.retryCount = im.retryCount + 1, im.lastError = :lastError,
			       im.failureReason = :failureReason
			 WHERE im.topic = :topic AND im.messageKey = :messageKey
			""")
	int markDlq(
			@Param("topic") String topic,
			@Param("messageKey") String messageKey,
			@Param("status") IssueMessageStatus status,
			@Param("lastError") String lastError,
			@Param("failureReason") IssueFailureReason failureReason
	);
	
	
	// Kafka Consumer가 coupon_issue 저장까지 성공했을 때(CONSUMED) 호출 — markDlq와 동일하게
	// topic+message_key(uk_message_key_topic) 기준. Kafka enqueue 성공(SENT)과 파이프라인 완주를
	// 구분하기 위한 것이라 retryCount/lastError는 건드리지 않는다.
	// 주의: clearAutomatically = true를 지정하면 CouponIssuePersister.persist() 트랜잭션 내에서
	// 함께 수정 중인 idempotency_key 등의 1차 캐시가 flush 전에 clear되어 dirty checking UPDATE가
	// 유실되므로 clearAutomatically를 지정하지 않는다.
	@Transactional
	@Modifying
	@Query("UPDATE IssueMessage im SET im.status = :status WHERE im.topic = :topic AND im.messageKey = :messageKey")
	int updateStatusByMessageKey(
			@Param("topic") String topic,
			@Param("messageKey") String messageKey,
			@Param("status") IssueMessageStatus status
	);

	boolean existsByTopicAndMessageKey(String topic, String messageKey);
	
	List<IssueMessage> findByStatusInAndRetryCountLessThan(
		Collection<IssueMessageStatus> statuses,
		int retryCount,
		Pageable pageable
	);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE IssueMessage im
			SET im.status = :status,
				im.processedAt = :processedAt,
				im.lastError = null,
				im.failureReason = null
		WHERE im.messageId = :messageId
	""")
	int markSent(
		@Param("messageId") Long messageId,
		@Param("status") IssueMessageStatus status,
		@Param("processedAt") LocalDateTime processedAt
	);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
	    UPDATE IssueMessage im
	       SET im.status = :status,
	           im.retryCount = im.retryCount + 1,
	           im.lastError = :lastError,
	           im.failureReason = :failureReason
	     WHERE im.messageId = :messageId
	""")
	int markPublishFailed(
	    @Param("messageId") Long messageId,
	    @Param("status") IssueMessageStatus status,
	    @Param("lastError") String lastError,
	    @Param("failureReason") IssueFailureReason failureReason
	);

	// 목록 조회용 — 재고 조회(findByStatusInAndRetryCountLessThan)와 동일하게 Pageable로 크기 제한.
	// coupon은 LAZY라 findAllByUserIdOrderByCreatedAtDesc와 같은 이유로 JOIN FETCH를 붙임.
	// (컨버터가 couponId만 읽어서 실측해보니 이 케이스는 지연로딩이어도 추가 쿼리/예외가 없었지만,
	// 나중에 컨버터가 coupon의 다른 필드를 읽게 되면 그때는 N+1/예외가 실제로 날 수 있어 방어적으로 유지)
	//
	// [PR 리뷰 반영] 실패 큐 목록 페이지네이션(#174) — 프론트가 전체 건수를 알아야 페이지 버튼을
	// 그릴 수 있어서 List 대신 Page를 반환한다. countQuery를 명시하는 이유는
	// CouponRepository.findCouponPage()와 같다 — Spring Data가 SELECT 쿼리에서 count 쿼리를
	// 자동으로 유도하게 두면 JOIN FETCH가 낀 쿼리에서 실패할 수 있어서, 직접 짠 count 쿼리를 쓴다.
	// JOIN FETCH는 count 쿼리에 안 옮긴다 — 개수만 세면 되고, count(im)엔 fetch join이 의미 없다
	// (오히려 JPA 스펙상 스칼라/집계 셀렉트에 fetch join을 못 쓴다).
	//
	// [PR 리뷰 반영] createdAt만으로 정렬하면 유일성이 보장 안 된다 — datetime(6)이라 지금은
	// 우연히 안 겹치지만, 여러 메시지가 같은 마이크로초에 DLQ로 처리되면 페이지마다 순서가
	// 흔들려 같은 행이 두 번 보이거나 빠질 수 있다. CouponRepository.findCouponPage()가
	// createdAt+couponId로 유일성을 보장하는 것과 같은 이유로 messageId(PK)를 tie-breaker로
	// 추가한다. idx_issue_message_dlq_list(status, created_at, message_id) 인덱스가 이
	// 정렬 순서를 그대로 커버해서 filesort 없이 나간다(EXPLAIN으로 확인 — IssueMessage.java
	// 참고).
	@Query(value = """
			SELECT im FROM IssueMessage im
			JOIN FETCH im.coupon
			WHERE im.status = :status
			ORDER BY im.createdAt ASC, im.messageId ASC
			""",
			countQuery = """
			SELECT count(im) FROM IssueMessage im
			WHERE im.status = :status
			""")
	Page<IssueMessage> findByStatus(@Param("status") IssueMessageStatus status, Pageable pageable);

	// 관리자가 동시에(또는 중복 클릭으로) 같은 메시지를 재처리 요청해도 한 번만 Kafka로 재발행되도록,
	// retryCount를 낙관적 락처럼 사용해 선점함 — status는 DLQ로 그대로 둬서 Outbox 발행 poller
	// (findByStatusInAndRetryCountLessThan이 PENDING/FAILED만 봄)가 이 행을 건드리지 않게 함.
	// 영향받은 행이 0이면 DLQ가 아니거나, 그 사이 다른 요청이 먼저 선점한 것으로 판단
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE IssueMessage im
			   SET im.retryCount = im.retryCount + 1
			 WHERE im.messageId = :messageId AND im.status = :expectedStatus AND im.retryCount = :expectedRetryCount
			""")
	int claimForReprocess(
			@Param("messageId") Long messageId,
			@Param("expectedStatus") IssueMessageStatus expectedStatus,
			@Param("expectedRetryCount") int expectedRetryCount
	);

	// 관리자가 DLQ 재처리를 포기하고 재고를 복구할 때 사용 — claimForReprocess와 동일하게
	// retryCount를 낙관적 락으로 써서, 재처리 요청과 동시에 들어와도 둘 중 하나만 선점하게 한다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE IssueMessage im
			   SET im.status = :newStatus
			 WHERE im.messageId = :messageId AND im.status = :expectedStatus AND im.retryCount = :expectedRetryCount
			""")
	int claimForAbandon(
			@Param("messageId") Long messageId,
			@Param("expectedStatus") IssueMessageStatus expectedStatus,
			@Param("expectedRetryCount") int expectedRetryCount,
			@Param("newStatus") IssueMessageStatus newStatus
	);

	// abandon()이 restoreStock() 성공(RESTORED/ALREADY_RESTORED)을 확인한 뒤에만 호출한다(#149).
	// status(ABANDONED)만으로는 재고 복구 성공 여부를 알 수 없어 — claimForAbandon()이 먼저
	// status를 커밋하고 그 다음에 restoreStock()을 호출하는 구조라, restoreStock()이 실패해도
	// status는 이미 ABANDONED로 남는다. 이 컬럼이 null인 ABANDONED 건만 정합성 검증 배치
	// (stockNotRestoredReader)가 "미복구"로 잡는다.
	//
	// stockRestoredAt IS NULL 조건을 건다 — restoreStock() 성공 직후, 이 UPDATE 전에 앱이
	// 죽는 등으로 기록이 누락되면 CouponIssueDlqReprocessServiceImpl.abandon()이 재시도로
	// 다시 이 메서드를 부르는데, 그때 이미 기록된 최초 복구 시각을 재호출 시각으로 덮어쓰지
	// 않기 위함이다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE IssueMessage im
			   SET im.stockRestoredAt = :restoredAt
			 WHERE im.messageId = :messageId
			   AND im.status = :expectedStatus
			   AND im.stockRestoredAt IS NULL
			""")
	int markStockRestored(
			@Param("messageId") Long messageId,
			@Param("restoredAt") LocalDateTime restoredAt,
			@Param("expectedStatus") IssueMessageStatus expectedStatus
	);

	// 발급 처리량 조회(#156)용 — 시간대(1시간 단위)별로 발급 성공(CONSUMED)/최종 실패(DLQ·
	// ABANDONED) 건수를 집계한다. DATE_FORMAT으로 시간 버킷을 만드는 건 JPQL로 표현이 안 돼서
	// 네이티브 쿼리를 쓴다. [from, to) 범위만 대상으로 해서 대시보드가 "최근 N시간" 같은
	// 범위로 좁혀 쓸 수 있게 한다 — 이력이 쌓일수록 전체를 긁지 않기 위함.
	//
	// [PR 리뷰 반영] since를 그냥 now().minus(24h)로만 잡으면 정각에 안 맞아서, 시:분에 따라
	// 맨 앞 버킷이 예를 들어 30분치만 담긴 채로 나머지 정각 버킷들과 나란히 그려진다 —
	// 그래프에서 그 시간대만 유독 낮아 보이는 왜곡이 생긴다. 그래서 서비스(IssueStatisticsService)가
	// 정각으로 자른 from/to를 넘겨서, 맨 앞 버킷도 항상 정각~정각 1시간 단위가 되게 한다
	// (가장 최근 버킷만 "진행 중이라 아직 안 찬" 상태인 게 자연스럽고, 그건 이 방식으로도 그대로 남는다).
	//
	// [지표 정의 — PR 리뷰 반영] created_at(메시지 생성 시각) 기준으로 버킷을 나누고, 그
	// 버킷 안의 메시지들이 "조회 시점 현재" 어떤 상태인지를 센다. 즉 "그 시간대에 들어온
	// 요청들의 최신 결과"(요청 유입량 기준)이지, "그 시간대에 실제로 처리 완료된 건수"(완료
	// 시각 기준)가 아니다 — processedAt은 Kafka 발행(SENT) 성공 시각일 뿐 완료 시각이 아니라
	// 대체할 수 없다. 그래서 이 값은 확정된 이력이 아니라 스냅샷이다 — 예를 들어 10시에 생성된
	// 메시지가 FAILED였다가 11시 재시도로 CONSUMED되면, 10시 버킷을 다시 조회했을 때
	// failedCount는 줄고 issuedCount가 는다. "언제 처리가 끝났는지"가 아니라 "언제 들어온
	// 요청인지"를 축으로 삼기로 한 의도적 설계다(완료 시각 기준으로 바꾸려면 별도 컬럼이
	// 필요 — #156 PR 리뷰 코멘트 참고).
	//
	// FAILED는 실패에서 뺐다 — Outbox Poller(findByStatusInAndRetryCountLessThan)가 PENDING과
	// 함께 재시도 대상으로 다시 집어가는 "재시도 대기" 상태라 최종 실패가 아니다. 최종 실패는
	// DLQ(관리자 확인 대기)·ABANDONED(포기 확정)뿐이다.
	//
	// [PR 리뷰 반영] issuedCount/failedCount만 있으면 PENDING/SENT/FAILED(전부 "아직 확정 안
	// 됨" 상태)가 어느 쪽에도 안 잡혀서, 방금 들어온 요청이 많은 버킷(최근 시간대)일수록
	// 실제보다 적어 보인다 — 누적 막대 그래프를 그리면 어긋난다. inProgressCount로 이 셋을
	// 같이 묶어서, issuedCount+failedCount+inProgressCount = 그 버킷의 총 접수량이 항상
	// 맞게 만든다. FAILED도 여기 포함한 이유는 위에서 이미 "확정 아님"으로 분류했기 때문 —
	// failedCount에서 뺀 것과 짝을 맞춘다.
	@Query(value = """
			SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00:00') AS bucket,
			       SUM(CASE WHEN status = 'CONSUMED' THEN 1 ELSE 0 END) AS issuedCount,
			       SUM(CASE WHEN status IN ('DLQ', 'ABANDONED') THEN 1 ELSE 0 END) AS failedCount,
			       SUM(CASE WHEN status IN ('PENDING', 'SENT', 'FAILED') THEN 1 ELSE 0 END) AS inProgressCount
			  FROM issue_message
			 WHERE created_at >= :from
			   AND created_at < :to
			 GROUP BY bucket
			 ORDER BY bucket ASC
			""", nativeQuery = true)
	List<IssueThroughputBucket> findThroughputByHour(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

	// 상태 분포 조회(#156)용 — 시간 범위로 좁히지 않고 전체를 대상으로 한다. PENDING/DLQ 같은
	// 상태는 "지금 이 시점에 몇 건이 그 상태로 남아있는지"(현재 잔량)가 의미 있는 지표라,
	// findThroughputByHour처럼 최근 N시간으로 좁히면 오래전에 DLQ로 빠진 뒤 그대로 방치된
	// 건들을 놓치게 된다. 단순 GROUP BY라 JPQL로 충분하다(네이티브 쿼리 불필요).
	@Query("SELECT im.status AS status, COUNT(im) AS count FROM IssueMessage im GROUP BY im.status")
	List<IssueStatusCount> countGroupedByStatus();

	// 부하 테스트 현황 조회(#195)용 — 쿠폰 하나로 좁힌 파이프라인 상태 분포.
	// idx_issue_message_coupon_dlq(coupon_id, status, message_id)가 coupon_id+status를
	// 그대로 커버해서, countGroupedByStatus()와 달리 5초 폴링에도 풀스캔이 안 난다.
	@Query("SELECT im.status AS status, COUNT(im) AS count FROM IssueMessage im WHERE im.coupon.couponId = :couponId GROUP BY im.status")
	List<IssueStatusCount> countGroupedByStatusForCoupon(@Param("couponId") Long couponId);

	// 실패 사유 분류 집계(#195)용 — 아직 관리자 확인 대기 중인 DLQ만 대상으로 한다. ABANDONED는
	// 이미 처리(포기)가 끝난 건이라 다시 분류해서 보여줄 실익이 없다. idx_issue_message_coupon_dlq
	// (coupon_id, status, message_id)로 coupon_id+status 필터가 커버된다.
	@Query("""
			SELECT im.failureReason AS failureReason, COUNT(im) AS count FROM IssueMessage im
			 WHERE im.coupon.couponId = :couponId AND im.status = com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.DLQ
			 GROUP BY im.failureReason
			""")
	List<IssueFailureReasonCount> countDlqGroupedByFailureReasonForCoupon(@Param("couponId") Long couponId);
}
