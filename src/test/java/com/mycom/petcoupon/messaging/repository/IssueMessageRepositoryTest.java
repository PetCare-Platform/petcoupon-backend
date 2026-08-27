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
import org.springframework.data.domain.PageRequest;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Outbox Poller(findByStatusInAndRetryCountLessThan)가 DLQ 상태 메시지를 실제로
 * 조회 대상에서 제외하는지 검증. DLQ 수동 재처리가 실패해도 poison message가
 * 다시 자동 재시도 대상에 걸리면 안 된다는 리뷰 코멘트를 근거로 추가.
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
		issueMessageRepository.markPublishFailed(dlqMessage.getMessageId(), IssueMessageStatus.DLQ, "poison message");

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
		issueMessageRepository.markPublishFailed(dlq.getMessageId(), IssueMessageStatus.DLQ, "test dlq");

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
		issueMessageRepository.markPublishFailed(dlq.getMessageId(), IssueMessageStatus.DLQ, "test dlq");

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
	// 아니다 — failedCount에 잡히면 안 된다(PR #156 리뷰 반영). issuedCount에도 당연히 안 잡힌다.
	@Test
	void findThroughputByHour는_재시도_대기중인_FAILED를_실패로_세지_않는다() {
		LocalDateTime bucketTime = LocalDateTime.now().withMinute(45).withSecond(0).withNano(0);
		String bucketKey = bucketTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00"));
		LocalDateTime since = bucketTime.minusHours(1);

		long issuedBefore = findBucketCount(since, bucketKey, IssueThroughputBucket::getIssuedCount);
		long failedBefore = findBucketCount(since, bucketKey, IssueThroughputBucket::getFailedCount);

		IssueMessage failed = IssueMessage.pending(coupon, 30L, 30L, "throughput-retrying-failed-1", "{}");
		entityManager.persist(failed);
		entityManager.flush();
		issueMessageRepository.markPublishFailed(failed.getMessageId(), IssueMessageStatus.FAILED, "test retrying failed");

		entityManager.createNativeQuery("UPDATE issue_message SET created_at = :bucketTime WHERE message_id = :id")
				.setParameter("bucketTime", bucketTime)
				.setParameter("id", failed.getMessageId())
				.executeUpdate();

		entityManager.flush();
		entityManager.clear();

		long issuedAfter = findBucketCount(since, bucketKey, IssueThroughputBucket::getIssuedCount);
		long failedAfter = findBucketCount(since, bucketKey, IssueThroughputBucket::getFailedCount);

		assertThat(issuedAfter - issuedBefore).isZero();
		assertThat(failedAfter - failedBefore).isZero();
	}

	private long findBucketCount(
			LocalDateTime since, String bucketKey, java.util.function.ToLongFunction<IssueThroughputBucket> extractor
	) {
		return issueMessageRepository.findThroughputByHour(since).stream()
				.filter(b -> b.getBucket().equals(bucketKey))
				.mapToLong(extractor::applyAsLong)
				.findFirst()
				.orElse(0L);
	}
}
