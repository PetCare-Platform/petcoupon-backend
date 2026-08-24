package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class CouponIssuePersisterTest {

	private static final CouponIssueEvent EVENT = new CouponIssueEvent(
		1L, 10L, "request-1", 5L, "COUPON-CODE-1", LocalDateTime.now().plusDays(7)
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

		persister.persist(EVENT);

		CouponIssue savedCouponIssue = couponIssueCaptor.getValue();
		assertThat(savedCouponIssue.getSequenceNo()).isEqualTo(5L);
		assertThat(savedCouponIssue.getCouponCode()).isEqualTo("COUPON-CODE-1");
		assertThat(savedCouponIssue.getRequestId()).isEqualTo("request-1");
		assertThat(savedCouponIssue.getExpiresAt()).isEqualTo(EVENT.expiresAt());

		verify(couponStockRepository).increaseIssuedQuantity(1L);

		ArgumentCaptor<CouponIssueHistory> historyCaptor = ArgumentCaptor.forClass(CouponIssueHistory.class);
		verify(couponIssueHistoryRepository).save(historyCaptor.capture());

		CouponIssueHistory history = historyCaptor.getValue();
		assertThat(history.getFromStatus()).isEqualTo(IssueHistoryStatus.NONE);
		assertThat(history.getToStatus()).isEqualTo(IssueHistoryStatus.ISSUED);
		assertThat(history.getActorType()).isEqualTo(HistoryActorType.SYSTEM);
		assertThat(history.getCouponId()).isEqualTo(1L);
		assertThat(history.getUserId()).isEqualTo(10L);
	}
}
