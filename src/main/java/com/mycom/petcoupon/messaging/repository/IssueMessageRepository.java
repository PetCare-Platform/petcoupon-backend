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
	// 발행 성공 시 SENT 로 올린다. 종착 상태를 막는 조건이 없으면 뒤늦게 도착한 발행 콜백이
	// 이미 끝난 건을 SENT 로 되돌린다 — Kafka 가 같은 VPC 안이면 왕복이 1ms 미만이라, 발행
	// 콜백이 markSent 를 커밋하기 전에 Consumer 가 소비를 끝내고 markConsumed 까지 커밋하는
	// 순서가 실제로 발생한다(부하 테스트 실측 3~6ms 차, 발급 68,000건 중 3건). 두 UPDATE 가
	// 모두 조건이 없으면 나중에 커밋한 쪽이 이기므로 status 가 SENT 로 남는다. 발급은
	// 확정됐는데 파이프라인은 미소진으로 보여 초기화 API 와 정합성 검증 배치가 막힌다.
	//
	// ABANDONED 도 같은 이유로 막는다. 재고까지 복구하고 포기한 종착 상태인데 SENT 로
	// 되돌아가면 failureReason 과 stockRestoredAt 의 맥락이 사라진다.
	//
	// [#217] DLQ 도 이제 막는다 — 예전엔 "claimForReprocess 가 status 를 DLQ 로 둔 채
	// 재처리를 선점하므로 여기서 막으면 안 된다"고 일부러 열어뒀는데, 그러면 claim 을
	// 거치지 않은 호출(Outbox Poller 가 같은 메시지를 실수로 두 번 발행했을 때의 지연된
	// 콜백 등)도 그대로 DLQ -> SENT 로 통과시켜버리는 구멍이 있었다(실제 DB 테스트로 재현
	// 확인함). 지금은 claimForReprocess 가 DLQ -> REPROCESSING 으로 먼저 전이시키므로,
	// 진짜 관리자 재처리로 발행에 성공한 건은 REPROCESSING 상태에서 이 메서드를 타고
	// SENT 로 올라간다 — REPROCESSING 은 이 NOT IN 목록에 없으므로 그대로 통과된다.
	// claim 없이 걸려온 호출은 status 가 여전히 DLQ 라 이 조건에 걸려 무시된다(반환값 0).
	//
	// IdempotencyKeyRepository 의 provisional 분기와 같은 패턴이다 — "아직 확정 아님"인 쓰기가
	// 종착 상태를 덮어쓰지 못하게 조건으로 막는다.
	//
	// 이 조건 때문에 반환값 0 은 실패가 아니라 "이미 끝난 건이다"라는 정상 결과다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE IssueMessage im
			SET im.status = :status,
				im.processedAt = :processedAt,
				im.lastError = null,
				im.failureReason = null
		WHERE im.messageId = :messageId
		  AND im.status NOT IN (
		          com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.CONSUMED,
		          com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.ABANDONED,
		          com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.DLQ
		      )
	""")
	int markSent(
		@Param("messageId") Long messageId,
		@Param("status") IssueMessageStatus status,
		@Param("processedAt") LocalDateTime processedAt
	);

	// [PR 리뷰 반영] 이 UPDATE에는 원래 status 조건이 없었다 — 같은 메시지의 발행이 겹친
	// 상황(Outbox poller 자동 재시도 vs 관리자 DLQ 재처리)에서 한쪽이 먼저 성공해 SENT/CONSUMED로
	// 확정된 뒤, 다른 쪽의 지연된 실패 콜백이 도착하면 조건 없이 FAILED/DLQ로 덮어써 성공 상태를
	// 되돌릴 수 있었다(markSent가 이미 같은 이유로 종착 상태를 막는 것과 대칭되는 문제).
	// 일반 발행 실패(Outbox 경로)는 PENDING/FAILED에서 시작했을 때만, 관리자 재처리 실패는
	// REPROCESSING에서 시작했을 때만 상태를 바꾸도록 expectedStatuses로 좁힌다 —
	// CouponIssueEventProducer.markFailed 참고.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
	    UPDATE IssueMessage im
	       SET im.status = :status,
	           im.retryCount = im.retryCount + 1,
	           im.lastError = :lastError,
	           im.failureReason = :failureReason
	     WHERE im.messageId = :messageId
	       AND im.status IN :expectedStatuses
	""")
	int markPublishFailed(
	    @Param("messageId") Long messageId,
	    @Param("status") IssueMessageStatus status,
	    @Param("lastError") String lastError,
	    @Param("failureReason") IssueFailureReason failureReason,
	    @Param("expectedStatuses") Collection<IssueMessageStatus> expectedStatuses
	);

	// 위 조건부 UPDATE의 편의 오버로드 — 실제 발행 경로를 검증하지 않는 테스트 데이터 셋업
	// (다른 쿼리를 위한 선행 상태 준비)에서 매번 expectedStatuses를 넘기지 않아도 되게, 발행
	// 실패가 시작될 수 있는 모든 비종착 상태(PENDING/FAILED/REPROCESSING)를 기본으로 허용한다.
	// 프로덕션 코드는 이 오버로드를 쓰지 않는다 — CouponIssueEventProducer.markFailed는 항상
	// 위의 좁혀진 expectedStatuses를 명시해서 호출한다.
	default int markPublishFailed(
	    Long messageId, IssueMessageStatus status, String lastError, IssueFailureReason failureReason
	) {
	    return markPublishFailed(messageId, status, lastError, failureReason, List.of(
	            IssueMessageStatus.PENDING, IssueMessageStatus.FAILED, IssueMessageStatus.REPROCESSING
	    ));
	}

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
	// retryCount를 낙관적 락처럼 사용해 선점함. 영향받은 행이 0이면 DLQ가 아니거나, 그 사이
	// 다른 요청이 먼저 선점한 것으로 판단.
	//
	// [#217] status를 DLQ -> REPROCESSING으로 같이 바꾼다 — 예전엔 status를 DLQ로 그대로
	// 둬서 Outbox 발행 poller(findByStatusInAndRetryCountLessThan이 PENDING/FAILED만 봄)가
	// 이 행을 건드리지 않게 하는 목적만 있었는데, 그것만으로는 markSent가 claim 여부와
	// 무관하게 DLQ->SENT를 전부 허용해버리는 문제가 있었다. REPROCESSING으로 전이시켜야
	// markSent가 "진짜 이 claim을 통해 재처리된 건"만 SENT로 올릴 수 있다(markSent 주석 참고).
	// poller가 REPROCESSING 상태도 안 건드리는 건 여전히 유효하다 — PENDING/FAILED만 보므로.
	// claimedAt은 CouponIssueReprocessRecoveryScheduler가 오래된 REPROCESSING을 회수할 때 쓴다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE IssueMessage im
			   SET im.retryCount = im.retryCount + 1,
			       im.status = com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.REPROCESSING,
			       im.reprocessingClaimedAt = :claimedAt
			 WHERE im.messageId = :messageId AND im.status = :expectedStatus AND im.retryCount = :expectedRetryCount
			""")
	int claimForReprocess(
			@Param("messageId") Long messageId,
			@Param("expectedStatus") IssueMessageStatus expectedStatus,
			@Param("expectedRetryCount") int expectedRetryCount,
			@Param("claimedAt") LocalDateTime claimedAt
	);

	// 발행 콜백이 영영 안 돌아오면 REPROCESSING에 영구히 갇혀 DLQ 목록·재처리·포기 어디에도
	// 안 걸린다 — cutoff보다 오래된 것만 DLQ로 되돌려 다시 다룰 수 있게 한다. failureReason은
	// 안 건드린다 — 원래 사유(예: CONSUME_PROCESSING_FAILED)를 지우면 countPublishedByCoupon()이
	// 이미 발행된 건을 미발행으로 잘못 센다.
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE IssueMessage im
			   SET im.status = com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.DLQ,
			       im.lastError = :lastError
			 WHERE im.status = com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.REPROCESSING
			   AND im.reprocessingClaimedAt < :cutoff
			""")
	int recoverStaleReprocessingMessages(@Param("cutoff") LocalDateTime cutoff, @Param("lastError") String lastError);

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
	// REPROCESSING도 inProgressCount에 포함한다(네이티브 쿼리라 IssueMessageStatus.
	// IN_PROGRESS_STATUSES를 직접 못 씀 — 상태 추가 시 이 리터럴도 같이 확인).
	@Query(value = """
			SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00:00') AS bucket,
			       SUM(CASE WHEN status = 'CONSUMED' THEN 1 ELSE 0 END) AS issuedCount,
			       SUM(CASE WHEN status IN ('DLQ', 'ABANDONED') THEN 1 ELSE 0 END) AS failedCount,
			       SUM(CASE WHEN status IN ('PENDING', 'SENT', 'FAILED', 'REPROCESSING') THEN 1 ELSE 0 END) AS inProgressCount
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

	// 부하 테스트 현황 조회(#195/#200)용 — Kafka 발행에 실제로 성공한 건수. SENT/CONSUMED는
	// 무조건 발행된 것이고, DLQ/ABANDONED는 failureReason으로 갈린다 — KAFKA_PUBLISH_FAILED는
	// 발행 자체가 실패한 것(CouponIssueEventProducer.markFailed), CONSUME_PROCESSING_FAILED는
	// 발행엔 성공했는데 Consumer 처리가 실패한 것(CouponIssueEventRecoverer.markDlq)이다.
	// FAILED는 발행 실패(KAFKA_PUBLISH_FAILED) 전용 상태라 항상 제외한다 — Consumer 처리 실패는
	// 재시도 없이 곧장 DLQ로 가서 FAILED를 거치지 않는다(CouponIssueEventRecoverer가 상태를
	// 무조건 DLQ로 세팅). failureReason이 null인 옛 DLQ 행(컬럼 도입 전)은 발행 여부를 알 수
	// 없어 제외한다.
	//
	// REPROCESSING도 DLQ/ABANDONED와 같은 failureReason 조건으로 포함한다 — 안 그러면
	// CONSUME_PROCESSING_FAILED(발행은 이미 성공)였던 메시지가 재처리 중엔 이 카운트에서 빠진다.
	@Query("""
			SELECT COUNT(im) FROM IssueMessage im
			 WHERE im.coupon.couponId = :couponId
			   AND (im.status IN (com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.SENT,
			                       com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.CONSUMED)
			        OR (im.status IN (com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.DLQ,
			                           com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.ABANDONED,
			                           com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.REPROCESSING)
			            AND im.failureReason = com.mycom.petcoupon.messaging.entity.enums.IssueFailureReason.CONSUME_PROCESSING_FAILED))
			""")
	long countPublishedByCoupon(@Param("couponId") Long couponId);

	// 쿠폰별 초 단위 발급 처리량 시계열 조회(#198)용 — 특정 쿠폰의 [from, to) 기간 동안
	// bucketSeconds 단위로 발급 성공(CONSUMED)/최종 실패(DLQ·ABANDONED)/진행 중(PENDING·SENT·FAILED)
	// 건수를 집계한다.
	//
	// [타임존 독립성 보장] UNIX_TIMESTAMP/FROM_UNIXTIME은 MySQL 세션 타임존에 의존하여
	// JVM 타임존과 불일치 시 버킷이 어긋나는 문제가 있다. 기준 시각(:from)으로부터의
	// 초 차이(TIMESTAMPDIFF)와 DATE_ADD를 사용해 순수 LocalDateTime 연산으로 버킷을 계산함으로써
	// 타임존 설정과 bucketSeconds 값에 상관없이 자바의 버킷 슬롯과 항상 100% 일치하도록 보장한다.
	@Query(value = """
			SELECT DATE_FORMAT(
			           DATE_ADD(:from, INTERVAL FLOOR(TIMESTAMPDIFF(SECOND, :from, created_at) / :bucketSeconds) * :bucketSeconds SECOND),
			           '%Y-%m-%d %H:%i:%s'
			       ) AS bucket,
			       SUM(CASE WHEN status = 'CONSUMED' THEN 1 ELSE 0 END) AS issuedCount,
			       SUM(CASE WHEN status IN ('DLQ', 'ABANDONED') THEN 1 ELSE 0 END) AS failedCount,
			       SUM(CASE WHEN status IN ('PENDING', 'SENT', 'FAILED', 'REPROCESSING') THEN 1 ELSE 0 END) AS inProgressCount
			  FROM issue_message
			 WHERE coupon_id = :couponId
			   AND created_at >= :from
			   AND created_at < :to
			 GROUP BY bucket
			 ORDER BY bucket ASC
			""", nativeQuery = true)
	List<IssueThroughputBucket> findThroughputByCouponAndSeconds(
			@Param("couponId") Long couponId,
			@Param("bucketSeconds") int bucketSeconds,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to
	);
}
