package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueEventConsumer;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssuePersister;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;

@ExtendWith(MockitoExtension.class)
class CouponIssueEventConsumerTest {

	// requestId는 "issue:{idempotencyId}" 형식이어야 한다 — IdempotencyRequestIdCodec 참고
	private static final CouponIssueEvent EVENT = new CouponIssueEvent(
		1L, 10L, "issue:42", 5L, "COUPON-CODE-1", LocalDateTime.now().plusDays(7)
	);

	@Mock
	private CouponIssueRepository couponIssueRepository;

	@Mock
	private CouponIssuePersister couponIssuePersister;

	@InjectMocks
	private CouponIssueEventConsumer consumer;

	@Test
	void 이미_저장된_requestId면_저장을_시도하지_않고_idempotency_key만_확정한다() {
		CouponIssue existing = mock(CouponIssue.class);
		when(couponIssueRepository.findByRequestId("issue:42")).thenReturn(Optional.of(existing));

		consumer.consume(EVENT);

		verify(couponIssuePersister, never()).persist(EVENT);
		verify(couponIssuePersister).confirmIdempotencySucceeded(existing, "issue:42");
		verify(couponIssuePersister).markConsumed("issue:42");
	}

	@Test
	void 정상_이벤트는_Persister에_저장을_위임하고_알림을_기록한다() {
		when(couponIssueRepository.findByRequestId("issue:42")).thenReturn(Optional.empty());
		CouponIssue saved = mock(CouponIssue.class);
		when(couponIssuePersister.persist(EVENT)).thenReturn(saved);

		consumer.consume(EVENT);

		verify(couponIssuePersister).persist(EVENT);
		verify(couponIssuePersister).recordNotification(saved);
	}

	@Test
	void 저장중_재전달로_확인되면_예외없이_스킵하고_idempotency_key를_확정한다() {
		CouponIssue existing = mock(CouponIssue.class);
		when(couponIssueRepository.findByRequestId("issue:42"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(existing));

		doThrow(new DataIntegrityViolationException("duplicate"))
			.when(couponIssuePersister).persist(EVENT);

		assertThatCode(() -> consumer.consume(EVENT)).doesNotThrowAnyException();

		verify(couponIssuePersister).confirmIdempotencySucceeded(existing, "issue:42");
		verify(couponIssuePersister).markConsumed("issue:42");
	}

	@Test
	void 저장중_실제_제약위반이면_예외를_재전파해_재시도_DLQ_경로를_타게_한다() {
		when(couponIssueRepository.findByRequestId("issue:42"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.empty());

		DataIntegrityViolationException fkViolation = new DataIntegrityViolationException("fk violation");
		doThrow(fkViolation).when(couponIssuePersister).persist(EVENT);

		Throwable thrown = catchThrowable(() -> consumer.consume(EVENT));

		assertThat(thrown).isSameAs(fkViolation);
		verify(couponIssuePersister, never()).confirmIdempotencySucceeded(any(), eq("issue:42"));
		verify(couponIssuePersister, never()).markConsumed(any());
	}

	@Test
	void 그_외_예외는_그대로_전파되어_재시도_대상이_된다() {
		when(couponIssueRepository.findByRequestId("issue:42")).thenReturn(Optional.empty());

		doThrow(new RuntimeException("transient db error"))
			.when(couponIssuePersister).persist(EVENT);

		Throwable thrown = catchThrowable(() -> consumer.consume(EVENT));

		assertThat(thrown).isInstanceOf(RuntimeException.class);
	}
}
