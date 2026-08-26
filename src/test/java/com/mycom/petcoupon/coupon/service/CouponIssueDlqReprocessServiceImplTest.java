package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.mycom.petcoupon.coupon.converter.CouponIssueDlqConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqAbandonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueStockRestoreResult;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueStockRestoreStatus;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueEventProducer;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

@ExtendWith(MockitoExtension.class)
class CouponIssueDlqReprocessServiceImplTest {

	@Mock
	private IssueMessageRepository issueMessageRepository;

	@Mock
	private CouponIssueDlqConverter couponIssueDlqConverter;

	@Mock
	private CouponIssueEventProducer couponIssueEventProducer;

	@Mock
	private CouponIssueLuaService couponIssueLuaService;

	@InjectMocks
	private CouponIssueDlqReprocessServiceImpl couponIssueDlqReprocessService;

	@BeforeEach
	void setUp() {
		// @Value 필드는 순수 Mockito 단위 테스트에서 주입되지 않아 직접 세팅
		ReflectionTestUtils.setField(couponIssueDlqReprocessService, "listSize", 100);
	}

	@Test
	void listDlqMessages는_DLQ_상태인_메시지만_변환해서_반환한다() {
		IssueMessage issueMessage = mock(IssueMessage.class);
		CouponIssueDlqResponse response = CouponIssueDlqResponse.builder().messageId(1L).build();

		when(issueMessageRepository.findByStatus(eq(IssueMessageStatus.DLQ), any(Pageable.class)))
				.thenReturn(List.of(issueMessage));
		when(couponIssueDlqConverter.toDlqResponse(issueMessage)).thenReturn(response);

		List<CouponIssueDlqResponse> result = couponIssueDlqReprocessService.listDlqMessages();

		assertThat(result).containsExactly(response);
	}

	@Test
	void reprocess는_DLQ_메시지를_원자적으로_선점한_뒤_다시_발행한다() {
		IssueMessage issueMessage = mock(IssueMessage.class);
		when(issueMessage.getRetryCount()).thenReturn(1);

		CouponIssueDlqReprocessResponse response = CouponIssueDlqReprocessResponse.builder().messageId(1L).build();

		when(issueMessageRepository.findById(1L)).thenReturn(Optional.of(issueMessage));
		when(issueMessageRepository.claimForReprocess(1L, IssueMessageStatus.DLQ, 1))
				.thenReturn(1);
		when(couponIssueDlqConverter.toReprocessResponse(issueMessage)).thenReturn(response);

		CouponIssueDlqReprocessResponse result = couponIssueDlqReprocessService.reprocess(1L);

		assertThat(result).isEqualTo(response);
		verify(couponIssueEventProducer).publish(issueMessage);
	}

	@Test
	void reprocess는_메시지가_없으면_예외를_던진다() {
		when(issueMessageRepository.findById(1L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> couponIssueDlqReprocessService.reprocess(1L))
				.isInstanceOf(GeneralException.class)
				.extracting(ex -> ((GeneralException) ex).getErrorCode())
				.isEqualTo(CouponErrorCode.DLQ_MESSAGE_NOT_FOUND);

		verify(couponIssueEventProducer, never()).publish(any());
	}

	@Test
	void reprocess는_선점에_실패하면_예외를_던지고_재발행하지_않는다() {
		IssueMessage issueMessage = mock(IssueMessage.class);
		when(issueMessage.getRetryCount()).thenReturn(1);

		when(issueMessageRepository.findById(1L)).thenReturn(Optional.of(issueMessage));
		when(issueMessageRepository.claimForReprocess(1L, IssueMessageStatus.DLQ, 1))
				.thenReturn(0);

		assertThatThrownBy(() -> couponIssueDlqReprocessService.reprocess(1L))
				.isInstanceOf(GeneralException.class)
				.extracting(ex -> ((GeneralException) ex).getErrorCode())
				.isEqualTo(CouponErrorCode.NOT_DLQ_STATUS);

		verify(couponIssueEventProducer, never()).publish(any());
	}

	@Test
	void abandon는_DLQ_메시지를_원자적으로_선점한_뒤_재고를_복구한다() {
		Coupon coupon = mock(Coupon.class);
		when(coupon.getCouponId()).thenReturn(10L);

		IssueMessage issueMessage = mock(IssueMessage.class);
		when(issueMessage.getRetryCount()).thenReturn(1);
		when(issueMessage.getCoupon()).thenReturn(coupon);
		when(issueMessage.getUserId()).thenReturn(100L);
		when(issueMessage.getMessageKey()).thenReturn("request-1");
		when(issueMessage.getSequenceNo()).thenReturn(5L);

		CouponIssueStockRestoreResult restoreResult = CouponIssueStockRestoreResult.builder()
				.status(CouponIssueStockRestoreStatus.RESTORED)
				.remainingStock(9)
				.build();
		CouponIssueDlqAbandonResponse response = CouponIssueDlqAbandonResponse.builder()
				.messageId(1L)
				.requestId("request-1")
				.restoreStatus("RESTORED")
				.remainingStock(9)
				.build();

		when(issueMessageRepository.findById(1L)).thenReturn(Optional.of(issueMessage));
		when(issueMessageRepository.claimForAbandon(1L, IssueMessageStatus.DLQ, 1, IssueMessageStatus.ABANDONED))
				.thenReturn(1);
		when(couponIssueLuaService.restoreStock(10L, 100L, "request-1", 5L)).thenReturn(restoreResult);
		when(couponIssueDlqConverter.toAbandonResponse(issueMessage, restoreResult)).thenReturn(response);

		CouponIssueDlqAbandonResponse result = couponIssueDlqReprocessService.abandon(1L);

		assertThat(result).isEqualTo(response);
	}

	@Test
	void abandon는_메시지가_없으면_예외를_던진다() {
		when(issueMessageRepository.findById(1L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> couponIssueDlqReprocessService.abandon(1L))
				.isInstanceOf(GeneralException.class)
				.extracting(ex -> ((GeneralException) ex).getErrorCode())
				.isEqualTo(CouponErrorCode.DLQ_MESSAGE_NOT_FOUND);

		verify(couponIssueLuaService, never()).restoreStock(any(), any(), any(), any());
	}

	@Test
	void abandon는_선점에_실패하면_예외를_던지고_재고를_복구하지_않는다() {
		IssueMessage issueMessage = mock(IssueMessage.class);
		when(issueMessage.getRetryCount()).thenReturn(1);

		when(issueMessageRepository.findById(1L)).thenReturn(Optional.of(issueMessage));
		when(issueMessageRepository.claimForAbandon(1L, IssueMessageStatus.DLQ, 1, IssueMessageStatus.ABANDONED))
				.thenReturn(0);

		assertThatThrownBy(() -> couponIssueDlqReprocessService.abandon(1L))
				.isInstanceOf(GeneralException.class)
				.extracting(ex -> ((GeneralException) ex).getErrorCode())
				.isEqualTo(CouponErrorCode.NOT_DLQ_STATUS);

		verify(couponIssueLuaService, never()).restoreStock(any(), any(), any(), any());
	}

	// claimForAbandon()은 자체 @Transactional을 가진 별도 트랜잭션이라 이 시점에 이미 DB에 커밋돼 있다.
	// 이후 restoreStock()이 실패해도 되돌릴 방법이 없다는 걸 알고 받아들인 설계이므로,
	// 최소한 "선점은 이미 끝났고 예외가 그대로 호출자(503)에게 전파된다"는 동작만은 고정해둔다.
	@Test
	void abandon는_선점_커밋후_재고복구가_실패해도_선점은_이미_끝난_채로_예외가_전파된다() {
		Coupon coupon = mock(Coupon.class);
		when(coupon.getCouponId()).thenReturn(10L);

		IssueMessage issueMessage = mock(IssueMessage.class);
		when(issueMessage.getRetryCount()).thenReturn(1);
		when(issueMessage.getCoupon()).thenReturn(coupon);
		when(issueMessage.getUserId()).thenReturn(100L);
		when(issueMessage.getMessageKey()).thenReturn("request-1");
		when(issueMessage.getSequenceNo()).thenReturn(5L);

		when(issueMessageRepository.findById(1L)).thenReturn(Optional.of(issueMessage));
		when(issueMessageRepository.claimForAbandon(1L, IssueMessageStatus.DLQ, 1, IssueMessageStatus.ABANDONED))
				.thenReturn(1);
		when(couponIssueLuaService.restoreStock(10L, 100L, "request-1", 5L))
				.thenThrow(new GeneralException(CouponErrorCode.ISSUE_STOCK_RESTORE_FAILED));

		assertThatThrownBy(() -> couponIssueDlqReprocessService.abandon(1L))
				.isInstanceOf(GeneralException.class)
				.extracting(ex -> ((GeneralException) ex).getErrorCode())
				.isEqualTo(CouponErrorCode.ISSUE_STOCK_RESTORE_FAILED);

		// 선점(claimForAbandon)은 restoreStock 실패와 무관하게 이미 호출되어 커밋 대상이 된 뒤다
		verify(issueMessageRepository).claimForAbandon(1L, IssueMessageStatus.DLQ, 1, IssueMessageStatus.ABANDONED);
		verify(couponIssueDlqConverter, never()).toAbandonResponse(any(), any());
	}

	@Test
	void abandon는_재고복구_상태가_RESTORED가_아니어도_예외없이_응답을_반환한다() {
		Coupon coupon = mock(Coupon.class);
		when(coupon.getCouponId()).thenReturn(10L);

		IssueMessage issueMessage = mock(IssueMessage.class);
		when(issueMessage.getRetryCount()).thenReturn(1);
		when(issueMessage.getCoupon()).thenReturn(coupon);
		when(issueMessage.getUserId()).thenReturn(100L);
		when(issueMessage.getMessageKey()).thenReturn("request-1");
		when(issueMessage.getSequenceNo()).thenReturn(5L);

		CouponIssueStockRestoreResult restoreResult = CouponIssueStockRestoreResult.builder()
				.status(CouponIssueStockRestoreStatus.INCONSISTENT_STATE)
				.remainingStock(9)
				.build();
		CouponIssueDlqAbandonResponse response = CouponIssueDlqAbandonResponse.builder()
				.messageId(1L)
				.requestId("request-1")
				.restoreStatus("INCONSISTENT_STATE")
				.remainingStock(9)
				.build();

		when(issueMessageRepository.findById(1L)).thenReturn(Optional.of(issueMessage));
		when(issueMessageRepository.claimForAbandon(1L, IssueMessageStatus.DLQ, 1, IssueMessageStatus.ABANDONED))
				.thenReturn(1);
		when(couponIssueLuaService.restoreStock(10L, 100L, "request-1", 5L)).thenReturn(restoreResult);
		when(couponIssueDlqConverter.toAbandonResponse(issueMessage, restoreResult)).thenReturn(response);

		// 정합성 이상(INCONSISTENT_STATE)이어도 이미 ABANDONED로 커밋된 뒤라 예외를 던지지 않고
		// 응답으로만 상태를 알린다 — 대신 서비스 내부에서 WARN 로그를 남겨 운영에서 놓치지 않게 한다.
		CouponIssueDlqAbandonResponse result = couponIssueDlqReprocessService.abandon(1L);

		assertThat(result).isEqualTo(response);
	}
}
