package com.mycom.petcoupon.coupon.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.CouponIssueDlqConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueEventProducer;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueDlqReprocessServiceImpl implements CouponIssueDlqReprocessService {

	private final IssueMessageRepository issueMessageRepository;
	private final CouponIssueDlqConverter couponIssueDlqConverter;
	private final CouponIssueEventProducer couponIssueEventProducer;

	@Override
	public List<CouponIssueDlqResponse> listDlqMessages() {
		return issueMessageRepository.findAllByStatusOrderByCreatedAtAsc(IssueMessageStatus.DLQ).stream()
				.map(couponIssueDlqConverter::toDlqResponse)
				.toList();
	}

	@Override
	public CouponIssueDlqReprocessResponse reprocess(Long messageId) {
		IssueMessage issueMessage = issueMessageRepository.findById(messageId)
				.orElseThrow(() -> new GeneralException(CouponErrorCode.DLQ_MESSAGE_NOT_FOUND));

		if (issueMessage.getStatus() != IssueMessageStatus.DLQ) {
			throw new GeneralException(CouponErrorCode.NOT_DLQ_STATUS);
		}

		// Kafka 발행은 비동기(내부에서 성공/실패에 따라 issue_message 상태를 직접 갱신함) —
		// 관리자 요청은 재발행을 트리거만 하고 완료까지 기다리지 않음
		couponIssueEventProducer.publish(issueMessage);

		return couponIssueDlqConverter.toReprocessResponse(issueMessage);
	}
}
