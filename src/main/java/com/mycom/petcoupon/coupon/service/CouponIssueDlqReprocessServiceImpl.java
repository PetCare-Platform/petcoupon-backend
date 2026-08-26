package com.mycom.petcoupon.coupon.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

	// Outbox 발행 대상 조회(coupon.issue.outbox.batch-size)와 동일한 방식으로 목록 크기를 제한
	@Value("${coupon.issue.dlq.list-size:100}")
	private int listSize;

	@Override
	public List<CouponIssueDlqResponse> listDlqMessages() {
		Pageable pageable = PageRequest.of(0, listSize);

		return issueMessageRepository.findByStatus(IssueMessageStatus.DLQ, pageable).stream()
				.map(couponIssueDlqConverter::toDlqResponse)
				.toList();
	}

	@Override
	public CouponIssueDlqReprocessResponse reprocess(Long messageId) {
		IssueMessage issueMessage = issueMessageRepository.findById(messageId)
				.orElseThrow(() -> new GeneralException(CouponErrorCode.DLQ_MESSAGE_NOT_FOUND));

		// retryCount를 낙관적 락으로 써서 원자적으로 선점 — status는 DLQ 그대로 둬서
		// Outbox 발행 poller(PENDING/FAILED만 봄) 대상이 되지 않게 함. 동시/중복 요청 중
		// 하나만 retryCount 증가에 성공하고, 나머지는 0건이 되어 아래에서 걸러짐
		int claimedRows = issueMessageRepository.claimForReprocess(
				messageId, IssueMessageStatus.DLQ, issueMessage.getRetryCount()
		);

		if (claimedRows == 0) {
			throw new GeneralException(CouponErrorCode.NOT_DLQ_STATUS);
		}

		// Kafka 발행은 비동기(내부에서 성공/실패에 따라 issue_message 상태를 직접 갱신함) —
		// 관리자 요청은 재발행을 트리거만 하고 완료까지 기다리지 않음
		couponIssueEventProducer.publish(issueMessage);

		return couponIssueDlqConverter.toReprocessResponse(issueMessage);
	}
}
