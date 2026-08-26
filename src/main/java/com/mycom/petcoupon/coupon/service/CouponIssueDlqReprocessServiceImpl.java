package com.mycom.petcoupon.coupon.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.CouponIssueDlqConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqAbandonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueStockRestoreResult;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueStockRestoreStatus;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueEventProducer;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueDlqReprocessServiceImpl implements CouponIssueDlqReprocessService {

	private final IssueMessageRepository issueMessageRepository;
	private final CouponIssueDlqConverter couponIssueDlqConverter;
	private final CouponIssueEventProducer couponIssueEventProducer;
	private final CouponIssueLuaService couponIssueLuaService;

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

	@Override
	public CouponIssueDlqAbandonResponse abandon(Long messageId) {
		IssueMessage issueMessage = issueMessageRepository.findById(messageId)
				.orElseThrow(() -> new GeneralException(CouponErrorCode.DLQ_MESSAGE_NOT_FOUND));

		// reprocess()와 동일하게 retryCount를 낙관적 락으로 써서, 재처리 요청과 동시에 들어와도
		// 둘 중 하나만 선점하게 한다 — 재고 복구와 재발행이 같은 메시지에 동시에 일어나는 걸 막는다.
		int claimedRows = issueMessageRepository.claimForAbandon(
				messageId, IssueMessageStatus.DLQ, issueMessage.getRetryCount(), IssueMessageStatus.ABANDONED
		);

		if (claimedRows == 0) {
			throw new GeneralException(CouponErrorCode.NOT_DLQ_STATUS);
		}

		// 순서를 반대로(재고 복구 → 선점) 하면 안 된다 — reprocess()가 먼저 재처리를 선점해 실제로
		// 발급에 성공하는 것과 동시에 여기서 재고까지 복구해버리면, 정상 발급된 건의 재고를 잘못
		// 되돌리는 더 심각한 버그가 된다. 지금 순서라면 선점에 실패한 쪽은 재고 복구 자체를 안 한다.
		// 대신 여기서 restoreStock()이 실패하면(Redis 장애 등) status는 이미 ABANDONED로 커밋된 뒤라
		// DLQ 목록에서 사라져 재시도 UI가 없다 — 예외가 그대로 호출자에게 전파(503)되니 조용히 묻히진
		// 않지만, 그 이후엔 수동으로 Redis 상태를 확인해야 한다.
		CouponIssueStockRestoreResult restoreResult = couponIssueLuaService.restoreStock(
				issueMessage.getCoupon().getCouponId(),
				issueMessage.getUserId(),
				issueMessage.getMessageKey(),
				issueMessage.getSequenceNo()
		);

		// status는 이미 ABANDONED로 커밋된 뒤라 여기서 되돌릴 방법이 없다 — RESTORED가 아니면
		// 응답 필드만으로는 운영자가 놓치기 쉬우므로 서버 로그에도 남겨 grep으로 찾을 수 있게 한다.
		if (restoreResult.status() != CouponIssueStockRestoreStatus.RESTORED) {
			log.warn(
					"[DLQ Abandon] 재고 복구가 정상 완료되지 않았습니다. messageId={}, requestId={}, restoreStatus={}, remainingStock={}",
					messageId, issueMessage.getMessageKey(), restoreResult.status(), restoreResult.remainingStock()
			);
		}

		return couponIssueDlqConverter.toAbandonResponse(issueMessage, restoreResult);
	}
}
