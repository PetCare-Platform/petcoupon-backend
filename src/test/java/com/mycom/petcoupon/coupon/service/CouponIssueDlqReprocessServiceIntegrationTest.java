package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import jakarta.persistence.EntityManagerFactory;

/**
 * listDlqMessages()가 @Transactional 없이 lazy(coupon)를 컨버터에서 접근하는 구조라,
 * Mockito 단위 테스트로는 실제 LazyInitializationException 여부를 검증할 수 없다.
 * @SpringBootTest로 실제 open-in-view=false 환경/트랜잭션 경계를 그대로 재현해서 확인한다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class CouponIssueDlqReprocessServiceIntegrationTest {

	@Autowired
	private CouponIssueDlqReprocessService couponIssueDlqReprocessService;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private IssueMessageRepository issueMessageRepository;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private final List<Long> couponIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		issueMessageRepository.deleteAll();
		couponIds.forEach(couponRepository::deleteById);
		couponIds.clear();
	}

	@Test
	void listDlqMessages는_트랜잭션_밖에서도_coupon_정보를_예외없이_반환한다() {
		LocalDateTime now = LocalDateTime.now();

		AppUser user = appUserRepository.saveAndFlush(
				AppUser.builder().name("DLQ 테스트 사용자")
						.email("dlq-" + UUID.randomUUID() + "@test.com").phone("01012345678").build()
		);

		Event event = eventRepository.saveAndFlush(
				Event.builder().createdBy(user).name("DLQ 테스트 이벤트").description("설명")
						.openAt(now.minusHours(1)).closeAt(now.plusDays(1)).build()
		);

		Coupon coupon = couponRepository.saveAndFlush(
				Coupon.builder().event(event).name("DLQ 테스트 쿠폰").discountType(DiscountType.values()[0])
						.discountValue(1_000).minOrderAmount(10_000).maxDiscountAmount(null)
						.issueStartAt(now.minusMinutes(10)).issueEndAt(now.plusHours(1)).validDays(7).build()
		);
		couponIds.add(coupon.getCouponId());

		String requestId = "dlq-test-" + UUID.randomUUID();
		IssueMessage issueMessage = issueMessageRepository.saveAndFlush(
				IssueMessage.pending(coupon, user.getUserId(), 1L, requestId, "{}")
		);
		issueMessageRepository.markDlq(
				KafkaTopics.COUPON_ISSUE_EVENT, requestId, IssueMessageStatus.DLQ, "test error"
		);

		// 서비스 메서드는 @Transactional이 아니므로, 여기서 실제로 세션이 닫힌 뒤 컨버터가 접근하는
		// 흐름이 그대로 재현됨 — JOIN FETCH가 없으면 여기서 LazyInitializationException이 터짐
		List<CouponIssueDlqResponse> result = couponIssueDlqReprocessService.listDlqMessages();

		CouponIssueDlqResponse found = result.stream()
				.filter(r -> r.messageId().equals(issueMessage.getMessageId()))
				.findFirst()
				.orElseThrow();

		assertThat(found.couponId()).isEqualTo(coupon.getCouponId());
		assertThat(found.requestId()).isEqualTo(requestId);
	}

	@Test
	void listDlqMessages는_쿠폰이_여러개여도_쿼리를_한번만_날린다() {
		LocalDateTime now = LocalDateTime.now();

		AppUser user = appUserRepository.saveAndFlush(
				AppUser.builder().name("DLQ 성능 테스트 사용자")
						.email("dlq-perf-" + UUID.randomUUID() + "@test.com").phone("01012345678").build()
		);

		Event event = eventRepository.saveAndFlush(
				Event.builder().createdBy(user).name("DLQ 성능 테스트 이벤트").description("설명")
						.openAt(now.minusHours(1)).closeAt(now.plusDays(1)).build()
		);

		// 서로 다른 쿠폰 3개 + 각각 DLQ 메시지 1개씩 — JOIN FETCH 없으면 쿠폰마다 별도 쿼리(N+1)가 나가야 함
		for (int i = 0; i < 3; i++) {
			Coupon coupon = couponRepository.saveAndFlush(
					Coupon.builder().event(event).name("DLQ 성능 테스트 쿠폰 " + i).discountType(DiscountType.values()[0])
							.discountValue(1_000).minOrderAmount(10_000).maxDiscountAmount(null)
							.issueStartAt(now.minusMinutes(10)).issueEndAt(now.plusHours(1)).validDays(7).build()
			);
			couponIds.add(coupon.getCouponId());

			String requestId = "dlq-perf-" + UUID.randomUUID();
			issueMessageRepository.saveAndFlush(
					IssueMessage.pending(coupon, user.getUserId(), 1L, requestId, "{}")
			);
			issueMessageRepository.markDlq(
					KafkaTopics.COUPON_ISSUE_EVENT, requestId, IssueMessageStatus.DLQ, "test error"
			);
		}

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		List<CouponIssueDlqResponse> result = couponIssueDlqReprocessService.listDlqMessages();

		long queryCount = statistics.getQueryExecutionCount();

		assertThat(result).hasSizeGreaterThanOrEqualTo(3);
		// JOIN FETCH로 한 방에 가져오면 1건, 없으면 목록조회 1 + 쿠폰별 지연로딩 3 = 4건 이상이어야 함
		assertThat(queryCount)
				.as("listDlqMessages 호출 시 실행된 쿼리 수 (JOIN FETCH 있으면 1이어야 함)")
				.isEqualTo(1);
	}
}
