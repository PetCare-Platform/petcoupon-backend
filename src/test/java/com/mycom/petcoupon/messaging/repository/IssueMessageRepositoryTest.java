package com.mycom.petcoupon.messaging.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

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
}
