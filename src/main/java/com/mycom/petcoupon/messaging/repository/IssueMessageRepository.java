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
}
