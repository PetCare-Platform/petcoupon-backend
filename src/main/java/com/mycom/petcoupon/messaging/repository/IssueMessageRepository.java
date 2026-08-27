package com.mycom.petcoupon.messaging.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.messaging.entity.IssueMessage;
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
			   SET im.status = :status, im.retryCount = im.retryCount + 1, im.lastError = :lastError
			 WHERE im.topic = :topic AND im.messageKey = :messageKey
			""")
	int markDlq(
			@Param("topic") String topic,
			@Param("messageKey") String messageKey,
			@Param("status") IssueMessageStatus status,
			@Param("lastError") String lastError
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
				im.lastError = null
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
	           im.lastError = :lastError
	     WHERE im.messageId = :messageId
	""")
	int markPublishFailed(
	    @Param("messageId") Long messageId,
	    @Param("status") IssueMessageStatus status,
	    @Param("lastError") String lastError
	);

	// 목록 조회용 — 재고 조회(findByStatusInAndRetryCountLessThan)와 동일하게 Pageable로 크기 제한.
	// coupon은 LAZY라 findAllByUserIdOrderByCreatedAtDesc와 같은 이유로 JOIN FETCH를 붙임.
	// (컨버터가 couponId만 읽어서 실측해보니 이 케이스는 지연로딩이어도 추가 쿼리/예외가 없었지만,
	// 나중에 컨버터가 coupon의 다른 필드를 읽게 되면 그때는 N+1/예외가 실제로 날 수 있어 방어적으로 유지)
	@Query("""
			SELECT im FROM IssueMessage im
			JOIN FETCH im.coupon
			WHERE im.status = :status
			ORDER BY im.createdAt ASC
			""")
	List<IssueMessage> findByStatus(@Param("status") IssueMessageStatus status, Pageable pageable);

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
}
