package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.converter.CouponIssueDlqConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueEventProducer;
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

	@InjectMocks
	private CouponIssueDlqReprocessServiceImpl couponIssueDlqReprocessService;

	@Test
	void listDlqMessages는_DLQ_상태인_메시지만_변환해서_반환한다() {
		IssueMessage issueMessage = mock(IssueMessage.class);
		CouponIssueDlqResponse response = CouponIssueDlqResponse.builder().messageId(1L).build();

		when(issueMessageRepository.findAllByStatusOrderByCreatedAtAsc(IssueMessageStatus.DLQ))
				.thenReturn(List.of(issueMessage));
		when(couponIssueDlqConverter.toDlqResponse(issueMessage)).thenReturn(response);

		List<CouponIssueDlqResponse> result = couponIssueDlqReprocessService.listDlqMessages();

		assertThat(result).containsExactly(response);
	}

	@Test
	void reprocess는_DLQ_메시지를_다시_발행하고_결과를_반환한다() {
		IssueMessage issueMessage = mock(IssueMessage.class);
		when(issueMessage.getStatus()).thenReturn(IssueMessageStatus.DLQ);

		CouponIssueDlqReprocessResponse response = CouponIssueDlqReprocessResponse.builder().messageId(1L).build();

		when(issueMessageRepository.findById(1L)).thenReturn(Optional.of(issueMessage));
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
	}

	@Test
	void reprocess는_DLQ_상태가_아니면_예외를_던진다() {
		IssueMessage issueMessage = mock(IssueMessage.class);
		when(issueMessage.getStatus()).thenReturn(IssueMessageStatus.SENT);

		when(issueMessageRepository.findById(1L)).thenReturn(Optional.of(issueMessage));

		assertThatThrownBy(() -> couponIssueDlqReprocessService.reprocess(1L))
				.isInstanceOf(GeneralException.class)
				.extracting(ex -> ((GeneralException) ex).getErrorCode())
				.isEqualTo(CouponErrorCode.NOT_DLQ_STATUS);
	}
}
