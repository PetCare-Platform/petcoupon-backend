package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.CouponIssueDlqConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqAbandonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqPageResponse;
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

	// [PR 리뷰 반영] 페이지네이션(#174) — page/size를 클라이언트가 지정할 수 있게 열면서
	// 고정 listSize(@Value)는 필요 없어졌다. 검증(0 이상, size는 10/20/50/100)은
	// CouponPageRequest 생성 시점에 이미 끝나 있어 여기서 다시 확인 안 한다.
	//
	// Page<T>.map()으로 content만 변환하고 페이지 메타(totalElements 등)는 그대로 들고
	// 간다 — CouponQueryServiceImpl.getCoupons()와 같은 패턴.
	@Override
	public CouponIssueDlqPageResponse listDlqMessages(CouponPageRequest pageRequest) {
		Pageable pageable = PageRequest.of(pageRequest.page(), pageRequest.size());

		Page<CouponIssueDlqResponse> dlqPage = issueMessageRepository
				.findByStatus(IssueMessageStatus.DLQ, pageable)
				.map(couponIssueDlqConverter::toDlqResponse);

		return CouponIssueDlqPageResponse.from(dlqPage);
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

		// 정상 경로: DLQ에서 처음 포기하는 경우.
		// 재시도 경로: 이미 ABANDONED로 커밋됐지만 restoreStock() 성공 직후 markStockRestored()
		// 전에 앱이 죽는 등으로 기록이 누락된 경우(#149 피드백) — 그대로 두면 재시도할 방법이
		// 없어 stock_restored_at=null인 채로 영구히 정합성 배치의 오탐 대상이 된다. Lua가
		// ALREADY_RESTORED를 반환하므로 restoreStock()을 다시 불러도 안전해서, 이 상태도
		// claimForAbandon 대상에 포함한다.
		boolean retryingIncompleteAbandon = issueMessage.getStatus() == IssueMessageStatus.ABANDONED
				&& issueMessage.getStockRestoredAt() == null;
		IssueMessageStatus expectedStatus = retryingIncompleteAbandon
				? IssueMessageStatus.ABANDONED
				: IssueMessageStatus.DLQ;

		// reprocess()와 동일하게 retryCount를 낙관적 락으로 써서, 재처리 요청과 동시에 들어와도
		// 둘 중 하나만 선점하게 한다 — 재고 복구와 재발행이 같은 메시지에 동시에 일어나는 걸 막는다.
		int claimedRows = issueMessageRepository.claimForAbandon(
				messageId, expectedStatus, issueMessage.getRetryCount(), IssueMessageStatus.ABANDONED
		);

		if (claimedRows == 0) {
			throw new GeneralException(CouponErrorCode.NOT_DLQ_STATUS);
		}

		// 순서를 반대로(재고 복구 → 선점) 하면 안 된다 — reprocess()가 먼저 재처리를 선점해 실제로
		// 발급에 성공하는 것과 동시에 여기서 재고까지 복구해버리면, 정상 발급된 건의 재고를 잘못
		// 되돌리는 더 심각한 버그가 된다. 지금 순서라면 선점에 실패한 쪽은 재고 복구 자체를 안 한다.
		// 여기서 restoreStock()이 실패하거나(Redis 장애 등) 성공 후 markStockRestored() 전에 앱이
		// 죽으면 status는 이미 ABANDONED로 커밋된 뒤라 DLQ 목록에서 사라진다 — 예외가 그대로
		// 호출자에게 전파(503)되니 조용히 묻히진 않고, 위 retryingIncompleteAbandon 분기 덕분에
		// 같은 messageId로 abandon을 다시 호출해 재시도할 수 있다(Lua가 ALREADY_RESTORED를
		// 반환하므로 재호출도 안전함).
		CouponIssueStockRestoreResult restoreResult = couponIssueLuaService.restoreStock(
				issueMessage.getCoupon().getCouponId(),
				issueMessage.getUserId(),
				issueMessage.getMessageKey(),
				issueMessage.getSequenceNo()
		);

		validateRestoreResult(messageId, issueMessage.getMessageKey(), restoreResult);

		// 복구가 확인된 뒤에만 기록한다(#149) — status(ABANDONED)만으로는 정합성 검증 배치가
		// 복구 성공 여부를 구분할 수 없어서, 이 컬럼이 별도의 "복구 확정" 신호가 된다.
		issueMessageRepository.markStockRestored(messageId, LocalDateTime.now(), IssueMessageStatus.ABANDONED);

		return couponIssueDlqConverter.toAbandonResponse(issueMessage, restoreResult);
	}

	// status는 이미 ABANDONED로 커밋된 뒤라 여기서 DB를 되돌릴 방법은 없다 — 대신 REQUEST_MISMATCH/
	// STOCK_NOT_INITIALIZED/INCONSISTENT_STATE처럼 정상 복구가 아닌 경우 응답을 성공(200)으로 주지
	// 않고 예외(503)로 명확히 알린다. ALREADY_RESTORED는 같은 요청이 이미 한 번 복구된 정상적인
	// 케이스라 실패로 보지 않는다.
	private void validateRestoreResult(Long messageId, String requestId, CouponIssueStockRestoreResult restoreResult) {
		if (restoreResult.status() != CouponIssueStockRestoreStatus.RESTORED
				&& restoreResult.status() != CouponIssueStockRestoreStatus.ALREADY_RESTORED) {
			log.warn(
					"[DLQ Abandon] 재고 복구가 정상 완료되지 않았습니다. messageId={}, requestId={}, restoreStatus={}, remainingStock={}",
					messageId, requestId, restoreResult.status(), restoreResult.remainingStock()
			);
			throw new GeneralException(CouponErrorCode.ISSUE_STOCK_RESTORE_FAILED);
		}
	}
}
