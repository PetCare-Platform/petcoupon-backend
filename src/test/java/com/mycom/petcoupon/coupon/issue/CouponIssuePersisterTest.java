package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponIssueHistory;
import com.mycom.petcoupon.coupon.entity.enums.HistoryActorType;
import com.mycom.petcoupon.coupon.entity.enums.IssueHistoryStatus;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssuePersister;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.repository.CouponIssueHistoryRepository;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.notification.entity.NotificationLog;
import com.mycom.petcoupon.notification.entity.enums.Channel;
import com.mycom.petcoupon.notification.entity.enums.NotificationStatus;
import com.mycom.petcoupon.notification.repository.NotificationLogRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CouponIssuePersisterTest {

	// requestId는 "issue:{idempotencyId}" 형식이어야 한다 — IdempotencyRequestIdCodec 참고
	private static final CouponIssueEvent EVENT = new CouponIssueEvent(
		1L, 10L, "issue:42", 5L, "COUPON-CODE-1", LocalDateTime.now().plusDays(7)
	);

	@Mock
	private CouponIssueRepository couponIssueRepository;

	@Mock
	private CouponRepository couponRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private CouponStockRepository couponStockRepository;

	@Mock
	private CouponIssueHistoryRepository couponIssueHistoryRepository;

	@Mock
	private CouponIssueConverter couponIssueConverter;

	@Mock
	private IdempotencyKeyService idempotencyKeyService;

	@Mock
	private ObjectMapper objectMapper;

	@Mock
	private IssueMessageRepository issueMessageRepository;

	@Mock
	private NotificationLogRepository notificationLogRepository;

	@InjectMocks
	private CouponIssuePersister persister;

	@Test
	void 이벤트를_받으면_발급_저장_재고증가_이력기록을_수행한다() {
		Coupon coupon = mock(Coupon.class);
		AppUser user = mock(AppUser.class);

		when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));

		ArgumentCaptor<CouponIssue> couponIssueCaptor = ArgumentCaptor.forClass(CouponIssue.class);
		when(couponIssueRepository.saveAndFlush(couponIssueCaptor.capture()))
			.thenAnswer(invocation -> invocation.getArgument(0));

		when(couponStockRepository.increaseIssuedQuantity(1L)).thenReturn(1);

		CouponIssueCreateResponse createResponse = CouponIssueCreateResponse.builder().couponId(1L).userId(10L).build();
		when(couponIssueConverter.toCreateResponse(any(CouponIssue.class))).thenReturn(createResponse);
		when(objectMapper.writeValueAsString(any())).thenReturn("{\"isSuccess\":true}");

		CouponIssue result = persister.persist(EVENT);

		CouponIssue savedCouponIssue = couponIssueCaptor.getValue();
		assertThat(savedCouponIssue.getSequenceNo()).isEqualTo(5L);
		assertThat(savedCouponIssue.getCouponCode()).isEqualTo("COUPON-CODE-1");
		assertThat(savedCouponIssue.getRequestId()).isEqualTo("issue:42");
		assertThat(savedCouponIssue.getExpiresAt()).isEqualTo(EVENT.expiresAt());
		assertThat(result).isSameAs(savedCouponIssue);

		verify(couponStockRepository).increaseIssuedQuantity(1L);

		ArgumentCaptor<CouponIssueHistory> historyCaptor = ArgumentCaptor.forClass(CouponIssueHistory.class);
		verify(couponIssueHistoryRepository).save(historyCaptor.capture());

		CouponIssueHistory history = historyCaptor.getValue();
		assertThat(history.getFromStatus()).isEqualTo(IssueHistoryStatus.NONE);
		assertThat(history.getToStatus()).isEqualTo(IssueHistoryStatus.ISSUED);
		assertThat(history.getActorType()).isEqualTo(HistoryActorType.SYSTEM);
		assertThat(history.getCouponId()).isEqualTo(1L);
		assertThat(history.getUserId()).isEqualTo(10L);

		// requestId "issue:42"에서 idempotency_id 42를 뽑아내 SUCCEEDED로 확정한다
		verify(idempotencyKeyService).succeed(42L, 200, "{\"isSuccess\":true}");

		verify(issueMessageRepository)
			.updateStatusByMessageKey(KafkaTopics.COUPON_ISSUE_EVENT, "issue:42", IssueMessageStatus.CONSUMED);

		// 알림 기록은 persist()와 별도 트랜잭션(recordNotification())으로 분리돼있어 여기서는 호출되지 않는다
		verifyNoInteractions(notificationLogRepository);
	}

	@Test
	void recordNotification_호출시_알림_로그가_저장된다() {
		AppUser user = mock(AppUser.class);
		when(user.getPhone()).thenReturn("010-1234-5678");

		CouponIssue couponIssue = CouponIssue.builder()
			.user(user)
			.couponCode("COUPON-CODE-1")
			.build();

		persister.recordNotification(couponIssue);

		ArgumentCaptor<NotificationLog> notificationCaptor = ArgumentCaptor.forClass(NotificationLog.class);
		verify(notificationLogRepository).save(notificationCaptor.capture());

		NotificationLog notification = notificationCaptor.getValue();
		assertThat(notification.getChannel()).isEqualTo(Channel.SMS);
		assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
		// #142 — 원본(010-1234-5678)이 아니라 마스킹된 값이 저장돼야 한다.
		// 이 단언이 평문으로 되돌아가면 마스킹이 빠진 것이다.
		assertThat(notification.getRecipientMasked())
				.isEqualTo("010-****-5678")
				.doesNotContain("1234");
		assertThat(notification.getSentAt()).isNotNull();
	}

	@Test
	void recordNotification_저장이_실패하면_예외를_그대로_전파한다() {
		AppUser user = mock(AppUser.class);
		when(user.getPhone()).thenReturn(null);

		CouponIssue couponIssue = CouponIssue.builder()
			.user(user)
			.couponCode("COUPON-CODE-1")
			.build();

		when(notificationLogRepository.save(any(NotificationLog.class)))
			.thenThrow(new org.springframework.dao.DataIntegrityViolationException("recipient_masked cannot be null"));

		// 예전엔 여기서 삼키려 했으나, 실측(CouponIssuePersisterIntegrationTest)으로 확인해보니
		// save() 실패 시점에 JPA 스펙상 트랜잭션이 이미 rollback-only로 마킹돼 메서드 안에서 삼켜도
		// @Transactional 프록시의 커밋 시도가 UnexpectedRollbackException을 새로 던진다. 그래서
		// 이 메서드는 삼키지 않고 그대로 던지며, 실패를 흡수하는 책임은 호출부(CouponIssueEventConsumer)에
		// 있다 — 이 Mockito 테스트는 실제 트랜잭션 커밋을 재현하지 못해 그 부분은 검증할 수 없다.
		Throwable thrown = catchThrowable(() -> persister.recordNotification(couponIssue));

		assertThat(thrown).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void requestId가_issue_형식이_아니면_idempotency_확정을_스킵하고_저장은_그대로_성공한다() {
		// CouponIssueStreamProducer를 직접 호출하는 경로(통합 테스트 등)는 idempotency_key를 안 거치므로
		// requestId가 "issue:{id}" 형식이 아닐 수 있다 — 이 경우 저장 자체는 정상적으로 끝나야 한다.
		CouponIssueEvent nonIdempotencyEvent = new CouponIssueEvent(
			1L, 10L, "pipeline-test-request", 5L, "COUPON-CODE-1", LocalDateTime.now().plusDays(7)
		);
		Coupon coupon = mock(Coupon.class);
		AppUser user = mock(AppUser.class);

		when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		when(couponIssueRepository.saveAndFlush(any(CouponIssue.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(couponStockRepository.increaseIssuedQuantity(1L)).thenReturn(1);

		CouponIssue result = persister.persist(nonIdempotencyEvent);

		assertThat(result.getRequestId()).isEqualTo("pipeline-test-request");
		verify(couponIssueHistoryRepository).save(any());
		verifyNoInteractions(idempotencyKeyService);

		// CONSUMED 배선은 idempotency_key 형식과 무관하게 항상 동작한다 (requestId를 그대로 message_key로 씀)
		verify(issueMessageRepository)
			.updateStatusByMessageKey(KafkaTopics.COUPON_ISSUE_EVENT, "pipeline-test-request", IssueMessageStatus.CONSUMED);
	}

	@Test
	void 재고_갱신이_0건이면_예외를_던져_트랜잭션이_롤백되게_한다() {
		Coupon coupon = mock(Coupon.class);
		AppUser user = mock(AppUser.class);

		when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
		when(appUserRepository.findById(10L)).thenReturn(Optional.of(user));
		when(couponIssueRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(CouponIssue.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		when(couponStockRepository.increaseIssuedQuantity(1L)).thenReturn(0);

		Throwable thrown = catchThrowable(() -> persister.persist(EVENT));

		assertThat(thrown).isInstanceOf(IllegalStateException.class);
		verify(couponIssueHistoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
		// 재고 갱신 실패로 이 시점 이후 로직(idempotency_key 확정, CONSUMED 배선, 알림 기록 포함)은 아예 실행되지 않는다
		verifyNoInteractions(idempotencyKeyService);
		verifyNoInteractions(issueMessageRepository);
		verifyNoInteractions(notificationLogRepository);
	}
}
