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
import com.mycom.petcoupon.coupon.repository.CouponIssueHistoryRepository;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
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

	@InjectMocks
	private CouponIssuePersister persister;

	@Test
	void 이벤트를_받으면_발급_저장_재고증가_이력기록을_수행한다() {
		Coupon coupon = mock(Coupon.class);
		AppUser user = mock(AppUser.class);

		when(couponRepository.getReferenceById(1L)).thenReturn(coupon);
		when(appUserRepository.getReferenceById(10L)).thenReturn(user);

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

		when(couponRepository.getReferenceById(1L)).thenReturn(coupon);
		when(appUserRepository.getReferenceById(10L)).thenReturn(user);
		when(couponIssueRepository.saveAndFlush(any(CouponIssue.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(couponStockRepository.increaseIssuedQuantity(1L)).thenReturn(1);

		CouponIssue result = persister.persist(nonIdempotencyEvent);

		assertThat(result.getRequestId()).isEqualTo("pipeline-test-request");
		verify(couponIssueHistoryRepository).save(any());
		verifyNoInteractions(idempotencyKeyService);
	}

	@Test
	void 재고_갱신이_0건이면_예외를_던져_트랜잭션이_롤백되게_한다() {
		Coupon coupon = mock(Coupon.class);
		AppUser user = mock(AppUser.class);

		when(couponRepository.getReferenceById(1L)).thenReturn(coupon);
		when(appUserRepository.getReferenceById(10L)).thenReturn(user);
		when(couponIssueRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(CouponIssue.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		when(couponStockRepository.increaseIssuedQuantity(1L)).thenReturn(0);

		Throwable thrown = catchThrowable(() -> persister.persist(EVENT));

		assertThat(thrown).isInstanceOf(IllegalStateException.class);
		verify(couponIssueHistoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
		// 재고 갱신 실패로 이 시점 이후 로직(idempotency_key 확정 포함)은 아예 실행되지 않는다
		verifyNoInteractions(idempotencyKeyService);
	}
}
