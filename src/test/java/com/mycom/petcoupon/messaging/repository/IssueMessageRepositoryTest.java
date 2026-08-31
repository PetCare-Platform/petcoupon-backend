package com.mycom.petcoupon.messaging.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueFailureReason;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * IssueMessageRepository의 커스텀 쿼리 검증. 처음엔 Outbox Poller(findByStatusInAndRetryCountLessThan)가
 * DLQ 상태 메시지를 재시도 대상에서 실제로 제외하는지 확인하려고 만들었고(DLQ 수동 재처리가
 * 실패해도 poison message가 다시 자동 재시도에 걸리면 안 된다는 리뷰 코멘트 근거),
 * 이후 발급 처리량/상태 분포 조회(#156, findThroughputByHour/countGroupedByStatus)
 * 검증도 여기 추가됐다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IssueMessageRepositoryTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private IssueMessageRepository issueMessageRepository;

	private Coupon coupon;

	@BeforeEach
	void setUp() {
		AppUser user = AppUser.builder()
				.name("IssueMessageRepository 테스트 사용자")
				.email("issue-message-repo-test@test.com")
				.phone("010-1234-5678")
				.build();
		entityManager.persist(user);

		LocalDateTime now = LocalDateTime.now();

		Event event = Event.builder()
				.createdBy(user)
				.name("IssueMessageRepository 테스트 이벤트")
				.description("설명")
				.openAt(now.minusHours(1))
				.closeAt(now.plusDays(1))
				.build();
		entityManager.persist(event);

		coupon = Coupon.builder()
				.event(event)
				.name("IssueMessageRepository 테스트 쿠폰")
				.discountType(DiscountType.values()[0])
				.discountValue(1_000)
				.minOrderAmount(10_000)
				.maxDiscountAmount(null)
				.issueStartAt(now.minusMinutes(10))
				.issueEndAt(now.plusHours(1))
				.validDays(7)
				.build();
		entityManager.persist(coupon);
	}

	@Test
	void DLQ_상태_메시지는_Outbox_재시도_조회_대상에서_제외된다() {
		IssueMessage dlqMessage = IssueMessage.pending(coupon, 1L, 1L, "dlq-request", "{}");
		entityManager.persist(dlqMessage);
		issueMessageRepository.markPublishFailed(dlqMessage.getMessageId(), IssueMessageStatus.DLQ, "poison message", IssueFailureReason.KAFKA_PUBLISH_FAILED);

		IssueMessage pendingMessage = IssueMessage.pending(coupon, 2L, 2L, "pending-request", "{}");
		entityManager.persist(pendingMessage);

		entityManager.flush();
		entityManager.clear();

		List<IssueMessage> result = issueMessageRepository.findByStatusInAndRetryCountLessThan(
				List.of(IssueMessageStatus.PENDING, IssueMessageStatus.FAILED),
				5,
				PageRequest.of(0, 10)
		);

		assertThat(result)
				.extracting(IssueMessage::getMessageKey)
				.contains("pending-request")
				.doesNotContain("dlq-request");
	}

	// 실패 큐 목록 페이지네이션(#174)용 — findByStatus가 Page를 반환하도록 바뀌면서 붙인
	// countQuery가 실제로 도는지 확인한다. Spring Data JPA는 결과가 요청한 페이지 크기보다
	// 작으면(=다음 페이지가 없다는 걸 content 크기만으로 알 수 있으면) countQuery 자체를
	// 건너뛴다(CouponIssueDlqReprocessServiceIntegrationTest에서 실측 확인) — 그래서
	// 페이지 크기를 1로 좁혀 결과가 꽉 차게(size=1) 만들어야 countQuery 경로가 실제로
	// 실행된다. 이게 없으면 countQuery에 오타·문법 오류가 있어도 어떤 테스트도 못 잡는다.
	@Test
	void findByStatus는_페이지가_꽉_찰_때도_전체_건수를_정확히_센다() {
		IssueMessage dlq1 = IssueMessage.pending(coupon, 70L, 70L, "dlq-page-1", "{}");
		entityManager.persist(dlq1);
		IssueMessage dlq2 = IssueMessage.pending(coupon, 71L, 71L, "dlq-page-2", "{}");
		entityManager.persist(dlq2);
		entityManager.flush();

		issueMessageRepository.markPublishFailed(dlq1.getMessageId(), IssueMessageStatus.DLQ, "test", IssueFailureReason.KAFKA_PUBLISH_FAILED);
		issueMessageRepository.markPublishFailed(dlq2.getMessageId(), IssueMessageStatus.DLQ, "test", IssueFailureReason.KAFKA_PUBLISH_FAILED);

		entityManager.flush();
		entityManager.clear();

		Page<IssueMessage> firstPage = issueMessageRepository.findByStatus(IssueMessageStatus.DLQ, PageRequest.of(0, 1));

		// 공유 DB에 다른 테스트가 남긴 DLQ 행이 섞여 있을 수 있어 절대값이 아니라 하한으로
		// 검증한다 — 이 테스트가 방금 넣은 2건만으로도 "1페이지 요청 시 content는 1건, 그런데
		// totalElements는 2건 이상"이 성립해야 countQuery가 실제로 동작한 것이다.
		assertThat(firstPage.getContent()).hasSize(1);
		assertThat(firstPage.getTotalElements()).isGreaterThanOrEqualTo(2L);
		assertThat(firstPage.getTotalPages()).isGreaterThanOrEqualTo(2);
	}

	// [PR 리뷰 반영] createdAt만으로는 정렬 순서가 유일하게 안 정해진다 — datetime(6)이라
	// 지금은 우연히 안 겹치지만, 여러 메시지가 같은 마이크로초에 DLQ로 처리되면 MySQL이 그
	// 안의 순서를 임의로 정해서 페이지마다 순서가 흔들릴 수 있다(CouponRepository.findCouponPage()가
	// createdAt+couponId로 유일성을 보장하는 것과 같은 문제). 두 메시지를 네이티브 UPDATE로
	// 똑같은 created_at에 강제로 맞춰놓고, messageId(PK, 오름차순 auto_increment) 기준으로
	// 순서가 고정되는지 확인한다.
	@Test
	void findByStatus는_createdAt이_같아도_messageId로_순서를_고정한다() {
		IssueMessage tieOlder = IssueMessage.pending(coupon, 80L, 80L, "dlq-tie-a", "{}");
		entityManager.persist(tieOlder);
		IssueMessage tieNewer = IssueMessage.pending(coupon, 81L, 81L, "dlq-tie-b", "{}");
		entityManager.persist(tieNewer);
		entityManager.flush();

		issueMessageRepository.markPublishFailed(tieOlder.getMessageId(), IssueMessageStatus.DLQ, "test", IssueFailureReason.KAFKA_PUBLISH_FAILED);
		issueMessageRepository.markPublishFailed(tieNewer.getMessageId(), IssueMessageStatus.DLQ, "test", IssueFailureReason.KAFKA_PUBLISH_FAILED);

		LocalDateTime sameInstant = LocalDateTime.now();
		for (IssueMessage message : List.of(tieOlder, tieNewer)) {
			entityManager.createNativeQuery("UPDATE issue_message SET created_at = :time WHERE message_id = :id")
					.setParameter("time", sameInstant)
					.setParameter("id", message.getMessageId())
					.executeUpdate();
		}

		entityManager.flush();
		entityManager.clear();

		List<Long> orderedIds = issueMessageRepository.findByStatus(IssueMessageStatus.DLQ, PageRequest.of(0, 1000))
				.getContent().stream()
				.map(IssueMessage::getMessageId)
				.filter(id -> id.equals(tieOlder.getMessageId()) || id.equals(tieNewer.getMessageId()))
				.toList();

		// tieOlder가 먼저 persist돼서 PK(auto_increment)가 항상 tieOlder < tieNewer다 —
		// createdAt이 완전히 같아도 이 순서가 매번 그대로 나와야 한다.
		assertThat(orderedIds).containsExactly(tieOlder.getMessageId(), tieNewer.getMessageId());
	}

	// countGroupedByStatus()는 특정 쿠폰/시간대로 좁히지 않는 전체 집계라, 공유 DB에
	// 다른 테스트가 남긴 행이 섞여 있을 수 있다 — 절대값이 아니라 "이 테스트가 새로 넣은 만큼
	// 늘었는지"(before/after 델타)로 검증해야 다른 테스트 데이터에 흔들리지 않는다.
	// JPQL 프로젝션이 IssueMessageStatus enum을 실제로 올바르게 매핑하는지도 이 테스트가
	// 확인한다(그냥 SQL로는 확인 안 되는, Spring Data 프로젝션 매핑 자체의 문제일 수 있어서).
	@Test
	void countGroupedByStatus는_상태별_건수를_정확히_집계한다() {
		Map<IssueMessageStatus, Long> before = issueMessageRepository.countGroupedByStatus().stream()
				.collect(Collectors.toMap(IssueStatusCount::getStatus, IssueStatusCount::getCount));

		IssueMessage pending1 = IssueMessage.pending(coupon, 10L, 10L, "status-count-pending-1", "{}");
		entityManager.persist(pending1);
		IssueMessage pending2 = IssueMessage.pending(coupon, 11L, 11L, "status-count-pending-2", "{}");
		entityManager.persist(pending2);

		IssueMessage consumed = IssueMessage.pending(coupon, 12L, 12L, "status-count-consumed", "{}");
		entityManager.persist(consumed);
		entityManager.flush();
		issueMessageRepository.updateStatus(consumed.getMessageId(), IssueMessageStatus.CONSUMED);

		IssueMessage dlq = IssueMessage.pending(coupon, 13L, 13L, "status-count-dlq", "{}");
		entityManager.persist(dlq);
		entityManager.flush();
		issueMessageRepository.markPublishFailed(dlq.getMessageId(), IssueMessageStatus.DLQ, "test dlq", IssueFailureReason.KAFKA_PUBLISH_FAILED);

		entityManager.flush();
		entityManager.clear();

		Map<IssueMessageStatus, Long> after = issueMessageRepository.countGroupedByStatus().stream()
				.collect(Collectors.toMap(IssueStatusCount::getStatus, IssueStatusCount::getCount));

		assertThat(after.getOrDefault(IssueMessageStatus.PENDING, 0L)
				- before.getOrDefault(IssueMessageStatus.PENDING, 0L)).isEqualTo(2L);
		assertThat(after.getOrDefault(IssueMessageStatus.CONSUMED, 0L)
				- before.getOrDefault(IssueMessageStatus.CONSUMED, 0L)).isEqualTo(1L);
		assertThat(after.getOrDefault(IssueMessageStatus.DLQ, 0L)
				- before.getOrDefault(IssueMessageStatus.DLQ, 0L)).isEqualTo(1L);
	}

	// findThroughputByHour()는 raw SQL로 로직만 확인했었지, 실제 Spring Data 프로젝션
	// (IssueThroughputBucket)을 거쳐 값이 제대로 들어오는지는 검증한 적이 없었다 — 특히
	// SUM(CASE...)는 MySQL에서 DECIMAL로 나올 수 있어 Long 매핑이 깨질 위험이 있다(리뷰 반영).
	// created_at은 @CreatedDate가 persist 시점 값을 넣으므로, 원하는 시간대에 테스트 데이터를
	// 두려면 네이티브 UPDATE로 덮어써야 한다. 같은 시간 버킷에 다른 테스트/실행이 남긴 데이터가
	// 섞일 수 있어 절대값이 아니라 delta로 검증한다.
	@Test
	void findThroughputByHour는_SUM_CASE_결과를_Long으로_정확히_매핑한다() {
		LocalDateTime bucketTime = LocalDateTime.now().withMinute(30).withSecond(0).withNano(0);
		String bucketKey = bucketTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00"));
		LocalDateTime since = bucketTime.minusHours(1);

		long issuedBefore = findBucketCount(since, bucketKey, IssueThroughputBucket::getIssuedCount);
		long failedBefore = findBucketCount(since, bucketKey, IssueThroughputBucket::getFailedCount);

		IssueMessage consumed1 = IssueMessage.pending(coupon, 20L, 20L, "throughput-consumed-1", "{}");
		entityManager.persist(consumed1);
		IssueMessage consumed2 = IssueMessage.pending(coupon, 21L, 21L, "throughput-consumed-2", "{}");
		entityManager.persist(consumed2);
		// failedCount는 최종 실패(DLQ/ABANDONED)만 세고 재시도 대기중인 FAILED는 안 세므로,
		// DLQ로 넣어야 이 케이스가 failedCount에 잡힌다(PR 리뷰 반영).
		IssueMessage dlq = IssueMessage.pending(coupon, 22L, 22L, "throughput-dlq-1", "{}");
		entityManager.persist(dlq);
		entityManager.flush();

		issueMessageRepository.updateStatus(consumed1.getMessageId(), IssueMessageStatus.CONSUMED);
		issueMessageRepository.updateStatus(consumed2.getMessageId(), IssueMessageStatus.CONSUMED);
		issueMessageRepository.markPublishFailed(dlq.getMessageId(), IssueMessageStatus.DLQ, "test dlq", IssueFailureReason.KAFKA_PUBLISH_FAILED);

		for (IssueMessage message : List.of(consumed1, consumed2, dlq)) {
			entityManager.createNativeQuery("UPDATE issue_message SET created_at = :bucketTime WHERE message_id = :id")
					.setParameter("bucketTime", bucketTime)
					.setParameter("id", message.getMessageId())
					.executeUpdate();
		}

		entityManager.flush();
		entityManager.clear();

		long issuedAfter = findBucketCount(since, bucketKey, IssueThroughputBucket::getIssuedCount);
		long failedAfter = findBucketCount(since, bucketKey, IssueThroughputBucket::getFailedCount);

		assertThat(issuedAfter - issuedBefore).isEqualTo(2L);
		assertThat(failedAfter - failedBefore).isEqualTo(1L);
	}

	// FAILED는 Outbox Poller가 재시도 대상으로 다시 집어가는 "재시도 대기" 상태라 최종 실패가
	// 아니다 — failedCount에 잡히면 안 된다(PR #156 리뷰 반영). issuedCount에도 당연히 안
	// 잡히고, 대신 "아직 확정 안 됨" 묶음인 inProgressCount에 잡혀야 한다.
	@Test
	void findThroughputByHour는_재시도_대기중인_FAILED를_실패_대신_진행중으로_센다() {
		LocalDateTime bucketTime = LocalDateTime.now().withMinute(45).withSecond(0).withNano(0);
		String bucketKey = bucketTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00"));
		LocalDateTime since = bucketTime.minusHours(1);

		long issuedBefore = findBucketCount(since, bucketKey, IssueThroughputBucket::getIssuedCount);
		long failedBefore = findBucketCount(since, bucketKey, IssueThroughputBucket::getFailedCount);
		long inProgressBefore = findBucketCount(since, bucketKey, IssueThroughputBucket::getInProgressCount);

		IssueMessage failed = IssueMessage.pending(coupon, 30L, 30L, "throughput-retrying-failed-1", "{}");
		entityManager.persist(failed);
		entityManager.flush();
		issueMessageRepository.markPublishFailed(failed.getMessageId(), IssueMessageStatus.FAILED, "test retrying failed", IssueFailureReason.KAFKA_PUBLISH_FAILED);

		entityManager.createNativeQuery("UPDATE issue_message SET created_at = :bucketTime WHERE message_id = :id")
				.setParameter("bucketTime", bucketTime)
				.setParameter("id", failed.getMessageId())
				.executeUpdate();

		entityManager.flush();
		entityManager.clear();

		long issuedAfter = findBucketCount(since, bucketKey, IssueThroughputBucket::getIssuedCount);
		long failedAfter = findBucketCount(since, bucketKey, IssueThroughputBucket::getFailedCount);
		long inProgressAfter = findBucketCount(since, bucketKey, IssueThroughputBucket::getInProgressCount);

		assertThat(issuedAfter - issuedBefore).isZero();
		assertThat(failedAfter - failedBefore).isZero();
		assertThat(inProgressAfter - inProgressBefore).isEqualTo(1L);
	}

	// PR 리뷰 반영 — issuedCount+failedCount+inProgressCount가 그 버킷의 총 접수량과 항상
	// 일치해야 프론트가 누적 막대 그래프를 그려도 어긋나지 않는다. 6개 상태(PENDING/SENT/
	// CONSUMED/FAILED/DLQ/ABANDONED)를 하나씩 넣어 세 카운트의 합이 6건과 같은지 확인한다.
	@Test
	void findThroughputByHour는_세_카운트의_합이_총_접수량과_같다() {
		LocalDateTime bucketTime = LocalDateTime.now().withMinute(15).withSecond(0).withNano(0);
		String bucketKey = bucketTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00"));
		LocalDateTime since = bucketTime.minusHours(1);

		long totalBefore = findBucketCount(since, bucketKey, IssueThroughputBucket::getIssuedCount)
				+ findBucketCount(since, bucketKey, IssueThroughputBucket::getFailedCount)
				+ findBucketCount(since, bucketKey, IssueThroughputBucket::getInProgressCount);

		IssueMessage pending = IssueMessage.pending(coupon, 40L, 40L, "total-pending", "{}");
		entityManager.persist(pending);
		IssueMessage sent = IssueMessage.pending(coupon, 41L, 41L, "total-sent", "{}");
		entityManager.persist(sent);
		IssueMessage consumed = IssueMessage.pending(coupon, 42L, 42L, "total-consumed", "{}");
		entityManager.persist(consumed);
		IssueMessage failed = IssueMessage.pending(coupon, 43L, 43L, "total-failed", "{}");
		entityManager.persist(failed);
		IssueMessage dlq = IssueMessage.pending(coupon, 44L, 44L, "total-dlq", "{}");
		entityManager.persist(dlq);
		IssueMessage abandoned = IssueMessage.pending(coupon, 45L, 45L, "total-abandoned", "{}");
		entityManager.persist(abandoned);
		entityManager.flush();

		issueMessageRepository.markSent(sent.getMessageId(), IssueMessageStatus.SENT, LocalDateTime.now());
		issueMessageRepository.updateStatus(consumed.getMessageId(), IssueMessageStatus.CONSUMED);
		issueMessageRepository.markPublishFailed(failed.getMessageId(), IssueMessageStatus.FAILED, "test", IssueFailureReason.KAFKA_PUBLISH_FAILED);
		issueMessageRepository.markPublishFailed(dlq.getMessageId(), IssueMessageStatus.DLQ, "test", IssueFailureReason.KAFKA_PUBLISH_FAILED);
		issueMessageRepository.markPublishFailed(abandoned.getMessageId(), IssueMessageStatus.ABANDONED, "test", IssueFailureReason.KAFKA_PUBLISH_FAILED);

		for (IssueMessage message : List.of(pending, sent, consumed, failed, dlq, abandoned)) {
			entityManager.createNativeQuery("UPDATE issue_message SET created_at = :bucketTime WHERE message_id = :id")
					.setParameter("bucketTime", bucketTime)
					.setParameter("id", message.getMessageId())
					.executeUpdate();
		}

		entityManager.flush();
		entityManager.clear();

		long totalAfter = findBucketCount(since, bucketKey, IssueThroughputBucket::getIssuedCount)
				+ findBucketCount(since, bucketKey, IssueThroughputBucket::getFailedCount)
				+ findBucketCount(since, bucketKey, IssueThroughputBucket::getInProgressCount);

		assertThat(totalAfter - totalBefore).isEqualTo(6L);
	}

	// PR 리뷰 반영 — from만 있고 to가 없던 구조가 맨 앞 버킷이 부분치만 담기는 문제의
	// 근본 원인이었다. to가 실제로 배타적 상한(created_at < to)으로 동작하는지 —
	// to 정각 그 자체는 다음 구간으로 빠지고, to 1초 전은 이번 구간에 포함되는지 —
	// 두 메시지를 각각 그 경계에 놓고 inProgressCount 델타로 확인한다.
	@Test
	void findThroughputByHour는_to_경계를_배타적으로_적용한다() {
		LocalDateTime to = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1);
		LocalDateTime from = to.minusHours(1);
		String bucketKey = from.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00"));

		long inProgressBefore = findBucketCountInRange(from, to, bucketKey, IssueThroughputBucket::getInProgressCount);

		IssueMessage justBeforeTo = IssueMessage.pending(coupon, 50L, 50L, "boundary-included", "{}");
		entityManager.persist(justBeforeTo);
		IssueMessage exactlyAtTo = IssueMessage.pending(coupon, 51L, 51L, "boundary-excluded", "{}");
		entityManager.persist(exactlyAtTo);
		entityManager.flush();

		entityManager.createNativeQuery("UPDATE issue_message SET created_at = :time WHERE message_id = :id")
				.setParameter("time", to.minusSeconds(1))
				.setParameter("id", justBeforeTo.getMessageId())
				.executeUpdate();
		entityManager.createNativeQuery("UPDATE issue_message SET created_at = :time WHERE message_id = :id")
				.setParameter("time", to)
				.setParameter("id", exactlyAtTo.getMessageId())
				.executeUpdate();

		entityManager.flush();
		entityManager.clear();

		long inProgressAfter = findBucketCountInRange(from, to, bucketKey, IssueThroughputBucket::getInProgressCount);

		// to 1초 전 것만 잡혀야 한다(+1) — to 그 자체인 것까지 잡히면(+2) 배타적 상한이
		// 깨진 것이다.
		assertThat(inProgressAfter - inProgressBefore).isEqualTo(1L);
	}

	private long findBucketCountInRange(
			LocalDateTime from, LocalDateTime to, String bucketKey,
			java.util.function.ToLongFunction<IssueThroughputBucket> extractor
	) {
		return issueMessageRepository.findThroughputByHour(from, to).stream()
				.filter(b -> b.getBucket().equals(bucketKey))
				.mapToLong(extractor::applyAsLong)
				.findFirst()
				.orElse(0L);
	}

	// to는 넉넉한 미래로 잡아서(이 테스트들은 to 경계를 검증 대상으로 삼지 않는다) since 이후
	// 전부가 조회되게 한다 — findThroughputByHour(from, to)의 to 경계 자체를 검증하는 건
	// 바로 위 findThroughputByHour는_to_경계를_배타적으로_적용한다() 전용이다.
	private long findBucketCount(
			LocalDateTime since, String bucketKey, java.util.function.ToLongFunction<IssueThroughputBucket> extractor
	) {
		return issueMessageRepository.findThroughputByHour(since, LocalDateTime.now().plusDays(1)).stream()
				.filter(b -> b.getBucket().equals(bucketKey))
				.mapToLong(extractor::applyAsLong)
				.findFirst()
				.orElse(0L);
	}

	// 실패 사유 분류 집계(#195)용. coupon이 테스트마다 새로 생성되므로(setUp) 절대값 검증이 가능하다.
	@Test
	void countDlqGroupedByFailureReasonForCoupon은_사유별_건수를_따로_센다() {
		IssueMessage publishFailed1 = IssueMessage.pending(coupon, 80L, 80L, "publish-failed-1", "{}");
		entityManager.persist(publishFailed1);
		IssueMessage publishFailed2 = IssueMessage.pending(coupon, 81L, 81L, "publish-failed-2", "{}");
		entityManager.persist(publishFailed2);
		IssueMessage consumeFailed = IssueMessage.pending(coupon, 82L, 82L, "consume-failed-1", "{}");
		entityManager.persist(consumeFailed);
		// DLQ가 아닌 FAILED는 집계 대상이 아니다 — 아직 재시도 대기 중이라 관리자가 볼 최종 실패가 아님
		IssueMessage stillRetrying = IssueMessage.pending(coupon, 83L, 83L, "still-retrying", "{}");
		entityManager.persist(stillRetrying);
		entityManager.flush();

		issueMessageRepository.markPublishFailed(
				publishFailed1.getMessageId(), IssueMessageStatus.DLQ, "kafka down", IssueFailureReason.KAFKA_PUBLISH_FAILED
		);
		issueMessageRepository.markPublishFailed(
				publishFailed2.getMessageId(), IssueMessageStatus.DLQ, "kafka down", IssueFailureReason.KAFKA_PUBLISH_FAILED
		);
		issueMessageRepository.markDlq(
				consumeFailed.getTopic(), consumeFailed.getMessageKey(), IssueMessageStatus.DLQ,
				"consume error", IssueFailureReason.CONSUME_PROCESSING_FAILED
		);
		issueMessageRepository.markPublishFailed(
				stillRetrying.getMessageId(), IssueMessageStatus.FAILED, "kafka down", IssueFailureReason.KAFKA_PUBLISH_FAILED
		);

		entityManager.flush();
		entityManager.clear();

		List<IssueFailureReasonCount> counts =
				issueMessageRepository.countDlqGroupedByFailureReasonForCoupon(coupon.getCouponId());

		Map<IssueFailureReason, Long> byReason = counts.stream()
				.collect(Collectors.toMap(IssueFailureReasonCount::getFailureReason, IssueFailureReasonCount::getCount));

		assertThat(byReason.get(IssueFailureReason.KAFKA_PUBLISH_FAILED)).isEqualTo(2L);
		assertThat(byReason.get(IssueFailureReason.CONSUME_PROCESSING_FAILED)).isEqualTo(1L);
	}

	@Test
	void findThroughputByCouponAndSeconds는_특정쿠폰의_초단위_집계를_정확히_수행한다() {
		LocalDateTime now = LocalDateTime.now().withNano(0);
		// 초를 5의 배수로 맞춘다
		int remainder = now.getSecond() % 5;
		LocalDateTime bucketStart = now.minusSeconds(remainder);
		String bucketKey = bucketStart.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

		LocalDateTime from = bucketStart.minusSeconds(10);
		LocalDateTime to = bucketStart.plusSeconds(10);

		// 1. 대상 쿠폰 메시지 생성
		IssueMessage consumed = IssueMessage.pending(coupon, 101L, 101L, "coupon-ts-consumed", "{}");
		entityManager.persist(consumed);
		IssueMessage dlq = IssueMessage.pending(coupon, 102L, 102L, "coupon-ts-dlq", "{}");
		entityManager.persist(dlq);
		IssueMessage pending = IssueMessage.pending(coupon, 103L, 103L, "coupon-ts-pending", "{}");
		entityManager.persist(pending);

		// 2. 다른 쿠폰 메시지 생성 (집계에서 제외되어야 함)
		Coupon otherCoupon = Coupon.builder()
				.event(coupon.getEvent())
				.name("다른 테스트 쿠폰")
				.discountType(coupon.getDiscountType())
				.discountValue(1_000)
				.minOrderAmount(10_000)
				.maxDiscountAmount(null)
				.issueStartAt(now.minusMinutes(10))
				.issueEndAt(now.plusHours(1))
				.validDays(7)
				.build();
		entityManager.persist(otherCoupon);

		IssueMessage otherConsumed = IssueMessage.pending(otherCoupon, 104L, 104L, "other-ts-consumed", "{}");
		entityManager.persist(otherConsumed);
		entityManager.flush();

		issueMessageRepository.updateStatus(consumed.getMessageId(), IssueMessageStatus.CONSUMED);
		issueMessageRepository.markPublishFailed(
				dlq.getMessageId(), IssueMessageStatus.DLQ, "test dlq", IssueFailureReason.KAFKA_PUBLISH_FAILED
		);
		issueMessageRepository.updateStatus(otherConsumed.getMessageId(), IssueMessageStatus.CONSUMED);

		for (IssueMessage msg : List.of(consumed, dlq, pending, otherConsumed)) {
			entityManager.createNativeQuery("UPDATE issue_message SET created_at = :time WHERE message_id = :id")
					.setParameter("time", bucketStart.plusSeconds(1))
					.setParameter("id", msg.getMessageId())
					.executeUpdate();
		}

		entityManager.flush();
		entityManager.clear();

		List<IssueThroughputBucket> buckets = issueMessageRepository.findThroughputByCouponAndSeconds(
				coupon.getCouponId(), 5, from, to
		);

		IssueThroughputBucket targetBucket = buckets.stream()
				.filter(b -> b.getBucket().equals(bucketKey))
				.findFirst()
				.orElseThrow();

		assertThat(targetBucket.getIssuedCount()).isEqualTo(1L);
		assertThat(targetBucket.getFailedCount()).isEqualTo(1L);
		assertThat(targetBucket.getInProgressCount()).isEqualTo(1L);
	}

	@Test
	void findThroughputByCouponAndSeconds는_9시간약수가아닌_7초버킷에서도_타임존과_무관하게_정확히_집계한다() {
		LocalDateTime from = LocalDateTime.now().withNano(0).minusMinutes(1);
		int bucketSeconds = 7;
		LocalDateTime to = from.plusSeconds(35); // 7초 버킷 5개

		// from + 3초 (1번째 버킷: [from, from+7s))
		IssueMessage msg1 = IssueMessage.pending(coupon, 201L, 201L, "coupon-ts-7s-1", "{}");
		// from + 10초 (2번째 버킷: [from+7s, from+14s))
		IssueMessage msg2 = IssueMessage.pending(coupon, 202L, 202L, "coupon-ts-7s-2", "{}");
		entityManager.persist(msg1);
		entityManager.persist(msg2);
		entityManager.flush();

		issueMessageRepository.updateStatus(msg1.getMessageId(), IssueMessageStatus.CONSUMED);
		issueMessageRepository.updateStatus(msg2.getMessageId(), IssueMessageStatus.CONSUMED);

		entityManager.createNativeQuery("UPDATE issue_message SET created_at = :time WHERE message_id = :id")
				.setParameter("time", from.plusSeconds(3))
				.setParameter("id", msg1.getMessageId())
				.executeUpdate();
		entityManager.createNativeQuery("UPDATE issue_message SET created_at = :time WHERE message_id = :id")
				.setParameter("time", from.plusSeconds(10))
				.setParameter("id", msg2.getMessageId())
				.executeUpdate();

		entityManager.flush();
		entityManager.clear();

		List<IssueThroughputBucket> buckets = issueMessageRepository.findThroughputByCouponAndSeconds(
				coupon.getCouponId(), bucketSeconds, from, to
		);

		String bucket1Key = from.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		String bucket2Key = from.plusSeconds(7).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

		IssueThroughputBucket bucket1 = buckets.stream()
				.filter(b -> b.getBucket().equals(bucket1Key))
				.findFirst()
				.orElseThrow();
		IssueThroughputBucket bucket2 = buckets.stream()
				.filter(b -> b.getBucket().equals(bucket2Key))
				.findFirst()
				.orElseThrow();

		assertThat(bucket1.getIssuedCount()).isEqualTo(1L);
		assertThat(bucket2.getIssuedCount()).isEqualTo(1L);
	}

	// [#200] 부하 테스트 현황의 published 계산 — SENT/CONSUMED는 무조건 세고, DLQ/ABANDONED는
	// failureReason으로 갈린다(KAFKA_PUBLISH_FAILED=발행 자체가 안 됨, CONSUME_PROCESSING_FAILED=
	// 발행은 됐는데 소비가 실패). PENDING과 failureReason이 null인 DLQ는 세지 않는다.
	@Test
	void countPublishedByCoupon은_발행에_성공한_건만_센다() {
		IssueMessage sent = IssueMessage.pending(coupon, 301L, 301L, "published-sent", "{}");
		IssueMessage consumed = IssueMessage.pending(coupon, 302L, 302L, "published-consumed", "{}");
		IssueMessage dlqPublishFailed = IssueMessage.pending(coupon, 303L, 303L, "published-dlq-publish-failed", "{}");
		IssueMessage dlqConsumeFailed = IssueMessage.pending(coupon, 304L, 304L, "published-dlq-consume-failed", "{}");
		IssueMessage abandonedConsumeFailed = IssueMessage.pending(coupon, 305L, 305L, "published-abandoned", "{}");
		IssueMessage dlqLegacyNullReason = IssueMessage.pending(coupon, 306L, 306L, "published-dlq-legacy", "{}");
		IssueMessage pending = IssueMessage.pending(coupon, 307L, 307L, "published-pending", "{}");
		List.of(sent, consumed, dlqPublishFailed, dlqConsumeFailed, abandonedConsumeFailed, dlqLegacyNullReason, pending)
				.forEach(entityManager::persist);
		entityManager.flush();

		issueMessageRepository.markSent(sent.getMessageId(), IssueMessageStatus.SENT, LocalDateTime.now());
		issueMessageRepository.updateStatus(consumed.getMessageId(), IssueMessageStatus.CONSUMED);
		issueMessageRepository.markPublishFailed(
				dlqPublishFailed.getMessageId(), IssueMessageStatus.DLQ, "publish failed", IssueFailureReason.KAFKA_PUBLISH_FAILED
		);
		issueMessageRepository.markDlq(
				KafkaTopics.COUPON_ISSUE_EVENT, "published-dlq-consume-failed", IssueMessageStatus.DLQ,
				"consume failed", IssueFailureReason.CONSUME_PROCESSING_FAILED
		);
		issueMessageRepository.markDlq(
				KafkaTopics.COUPON_ISSUE_EVENT, "published-abandoned", IssueMessageStatus.DLQ,
				"consume failed", IssueFailureReason.CONSUME_PROCESSING_FAILED
		);
		// failureReason 컬럼 도입 전(SEED 등)에 이미 DLQ였던 행을 흉내낸다 — 상태 갱신용
		// 리포지토리 메서드가 없어 직접 SQL로 만든다.
		entityManager.createNativeQuery(
				"UPDATE issue_message SET status = 'DLQ', failure_reason = NULL WHERE message_id = :id"
		).setParameter("id", dlqLegacyNullReason.getMessageId()).executeUpdate();

		entityManager.flush();
		entityManager.clear();

		// abandonedConsumeFailed를 DLQ -> ABANDONED로 포기 처리(claimForAbandon은 markDlq가
		// 올린 retryCount=1을 기대한다).
		int abandoned = issueMessageRepository.claimForAbandon(
				abandonedConsumeFailed.getMessageId(), IssueMessageStatus.DLQ, 1, IssueMessageStatus.ABANDONED
		);
		assertThat(abandoned).isEqualTo(1);

		long published = issueMessageRepository.countPublishedByCoupon(coupon.getCouponId());

		// SENT + CONSUMED + DLQ(consume-failed) + ABANDONED(consume-failed) = 4
		// PENDING, DLQ(publish-failed), DLQ(사유 없음)는 제외
		assertThat(published).isEqualTo(4L);
	}

	// Kafka 가 같은 VPC 안이면 왕복이 1ms 미만이라, 발행 콜백이 markSent 를 커밋하기 전에 Consumer 가
	// 소비를 끝내는 순서가 실제로 발생한다(부하 테스트에서 발급 68,000건 중 3건 실측).
	// 여기서 검증하는 것은 타이밍이 아니라 "종착 상태를 되돌리지 않는가"이므로 순서를 강제해서 재현한다.
	@Test
	void markSent는_이미_CONSUMED된_메시지를_SENT로_되돌리지_않는다() {
		IssueMessage message = IssueMessage.pending(coupon, 90L, 90L, "race-consumed-first", "{}");
		entityManager.persist(message);
		entityManager.flush();

		// Consumer 가 먼저 확정한 상태
		issueMessageRepository.updateStatus(message.getMessageId(), IssueMessageStatus.CONSUMED);
		entityManager.flush();
		entityManager.clear();

		// 뒤늦게 도착한 발행 콜백
		int updated = issueMessageRepository.markSent(
				message.getMessageId(), IssueMessageStatus.SENT, LocalDateTime.now()
		);

		// 0 은 실패가 아니라 "Consumer 가 먼저 확정했다"는 정상 결과다
		assertThat(updated).isZero();

		IssueMessage found = issueMessageRepository.findById(message.getMessageId()).orElseThrow();
		assertThat(found.getStatus()).isEqualTo(IssueMessageStatus.CONSUMED);
	}

	@Test
	void markSent는_아직_확정되지_않은_메시지는_SENT로_올린다() {
		IssueMessage message = IssueMessage.pending(coupon, 91L, 91L, "race-sent-first", "{}");
		entityManager.persist(message);
		entityManager.flush();
		entityManager.clear();

		int updated = issueMessageRepository.markSent(
				message.getMessageId(), IssueMessageStatus.SENT, LocalDateTime.now()
		);

		assertThat(updated).isEqualTo(1);

		IssueMessage found = issueMessageRepository.findById(message.getMessageId()).orElseThrow();
		assertThat(found.getStatus()).isEqualTo(IssueMessageStatus.SENT);
		assertThat(found.getProcessedAt()).isNotNull();
	}

	// ABANDONED 는 재고까지 복구하고 포기한 종착 상태다. 뒤늦은 발행 콜백이 SENT 로 되돌리면
	// failureReason 과 stockRestoredAt 의 맥락이 사라진다.
	@Test
	void markSent는_이미_ABANDONED된_메시지를_SENT로_되돌리지_않는다() {
		IssueMessage message = IssueMessage.pending(coupon, 92L, 92L, "race-abandoned", "{}");
		entityManager.persist(message);
		entityManager.flush();

		issueMessageRepository.markPublishFailed(
				message.getMessageId(), IssueMessageStatus.ABANDONED, "포기", IssueFailureReason.CONSUME_PROCESSING_FAILED
		);
		entityManager.flush();
		entityManager.clear();

		int updated = issueMessageRepository.markSent(
				message.getMessageId(), IssueMessageStatus.SENT, LocalDateTime.now()
		);

		assertThat(updated).isZero();

		IssueMessage found = issueMessageRepository.findById(message.getMessageId()).orElseThrow();
		assertThat(found.getStatus()).isEqualTo(IssueMessageStatus.ABANDONED);
		assertThat(found.getFailureReason()).isEqualTo(IssueFailureReason.CONSUME_PROCESSING_FAILED);
	}

	// [#217] claimForReprocess가 status를 DLQ -> REPROCESSING으로 전이시키며 선점하므로,
	// 관리자 재처리로 재발행에 성공한 건은 markSent로 REPROCESSING -> SENT가 되어야 한다.
	// REPROCESSING은 markSent의 NOT IN 목록에 없으므로 정상적으로 통과된다.
	@Test
	void markSent는_DLQ_재처리로_재발행에_성공하면_SENT로_올린다() {
		IssueMessage message = IssueMessage.pending(coupon, 93L, 93L, "dlq-reprocess", "{}");
		entityManager.persist(message);
		entityManager.flush();

		issueMessageRepository.markPublishFailed(
				message.getMessageId(), IssueMessageStatus.DLQ, "발행 실패", IssueFailureReason.KAFKA_PUBLISH_FAILED
		);
		entityManager.flush();
		entityManager.clear();

		// [#217] 관리자 재처리 선점 — status 는 DLQ 에서 REPROCESSING 으로 바뀐다
		int claimed = issueMessageRepository.claimForReprocess(message.getMessageId(), IssueMessageStatus.DLQ, 1);
		assertThat(claimed).isEqualTo(1);
		entityManager.flush();
		entityManager.clear();

		IssueMessage claimedMessage = issueMessageRepository.findById(message.getMessageId()).orElseThrow();
		assertThat(claimedMessage.getStatus()).isEqualTo(IssueMessageStatus.REPROCESSING);

		int updated = issueMessageRepository.markSent(
				message.getMessageId(), IssueMessageStatus.SENT, LocalDateTime.now()
		);

		assertThat(updated).isEqualTo(1);

		IssueMessage found = issueMessageRepository.findById(message.getMessageId()).orElseThrow();
		assertThat(found.getStatus()).isEqualTo(IssueMessageStatus.SENT);
		assertThat(found.getLastError()).isNull();
		assertThat(found.getFailureReason()).isNull();
	}

	// [#217 수정 완료] 예전엔 Outbox Poller(CouponIssueOutboxPublisher)가 메시지 행을 선점(claim)
	// 하지 않고 바로 publish()를 호출해서, 한 번의 발행이 poller 주기보다 오래 걸리면 같은 메시지가
	// 두 번 발행되고, 그중 하나가 실패해 DLQ로 전이된 직후 다른 하나의 지연된 성공 콜백이 markSent를
	// 호출하면 "관리자가 손댄 적 없는" DLQ가 조용히 SENT로 되돌아가는 문제가 있었다(실제 DB 테스트로
	// 재현 확인함, git 이력 참고).
	//
	// 지금은 claimForReprocess를 거쳐 REPROCESSING으로 전이된 건만 markSent가 DLQ 계열을 SENT로
	// 올린다. claimForReprocess 없이 곧바로 걸려온 호출(예: 위에서 말한 poller의 지연된 두 번째
	// 발행 시도)은 status가 여전히 DLQ라 markSent의 NOT IN 조건에 걸려 무시된다 — 이 테스트가 그걸
	// 확인한다. 위 markSent는_DLQ_재처리로_재발행에_성공하면_SENT로_올린다()와 대비된다 — 그 테스트는
	// claimForReprocess를 먼저 호출하지만, 이 테스트는 호출하지 않는다.
	@Test
	void markSent는_claimForReprocess_없이는_DLQ를_SENT로_바꾸지_못한다() {
		IssueMessage message = IssueMessage.pending(coupon, 94L, 94L, "dlq-no-claim", "{}");
		entityManager.persist(message);
		entityManager.flush();

		issueMessageRepository.markPublishFailed(
				message.getMessageId(), IssueMessageStatus.DLQ, "발행 실패", IssueFailureReason.KAFKA_PUBLISH_FAILED
		);
		entityManager.flush();
		entityManager.clear();

		// claimForReprocess를 호출하지 않은 채로 바로 markSent를 부른다 —
		// 관리자 재처리가 아니라 "poller의 지연된 두 번째 발행 시도"를 흉내낸다.
		int updated = issueMessageRepository.markSent(
				message.getMessageId(), IssueMessageStatus.SENT, LocalDateTime.now()
		);

		// [#217 수정 완료] claim 없이는 0건 갱신 — DLQ가 그대로 유지되어 관리자 목록에 남는다
		assertThat(updated).isZero();
		IssueMessage found = issueMessageRepository.findById(message.getMessageId()).orElseThrow();
		assertThat(found.getStatus()).isEqualTo(IssueMessageStatus.DLQ);
	}
}
