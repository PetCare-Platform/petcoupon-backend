package com.mycom.petcoupon.coupon.issue.consumer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponIssueHistory;
import com.mycom.petcoupon.coupon.entity.enums.HistoryActorType;
import com.mycom.petcoupon.coupon.entity.enums.IssueHistoryStatus;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.repository.CouponIssueHistoryRepository;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.idempotency.service.IdempotencyRequestIdCodec;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

// CouponIssueEventConsumer가 같은 클래스 내부 메서드를 호출하면 프록시를 안 거쳐 @Transactional이 무시되므로
// 별도 빈으로 분리함
@Component
@RequiredArgsConstructor
public class CouponIssuePersister {

	private final CouponIssueRepository couponIssueRepository;
	private final CouponRepository couponRepository;
	private final AppUserRepository appUserRepository;
	private final CouponStockRepository couponStockRepository;
	private final CouponIssueHistoryRepository couponIssueHistoryRepository;
	private final CouponIssueConverter couponIssueConverter;
	private final IdempotencyKeyService idempotencyKeyService;
	private final ObjectMapper objectMapper;
	private final IssueMessageRepository issueMessageRepository;

	@Transactional
	public CouponIssue persist(CouponIssueEvent event) {
		CouponIssue couponIssue = couponIssueRepository.saveAndFlush(
			CouponIssue.builder()
				.coupon(couponRepository.getReferenceById(event.couponId()))
				.user(appUserRepository.getReferenceById(event.userId()))
				.sequenceNo(event.sequenceNo())
				.couponCode(event.couponCode())
				.requestId(event.requestId())
				.expiresAt(event.expiresAt())
				.build()
		);

		int updatedRows = couponStockRepository.increaseIssuedQuantity(event.couponId());

		if (updatedRows == 0) {
			throw new IllegalStateException(
				"coupon_stock 갱신 실패(remaining_quantity 부족 또는 coupon_id 없음): couponId=" + event.couponId()
					+ ", requestId=" + event.requestId()
			);
		}

		couponIssueHistoryRepository.save(
			CouponIssueHistory.builder()
				.couponIssue(couponIssue)
				.couponId(event.couponId())
				.userId(event.userId())
				.fromStatus(IssueHistoryStatus.NONE)
				.toStatus(IssueHistoryStatus.ISSUED)
				.actorType(HistoryActorType.SYSTEM)
				.reason("Kafka Consumer 발급 확정")
				.build()
		);

		// coupon_issue insert/재고 증가/이력 기록과 같은 트랜잭션으로 묶어서 확정한다 — 둘 다 같은 MySQL이라
		// 여기서 분리하면 "발급은 커밋됐는데 idempotency_key는 IN_PROGRESS로 남는" 반쪽 상태가 생길 수 있다.
		confirmIdempotencySucceeded(couponIssue, event.requestId());

		// 위와 같은 이유로 같은 트랜잭션에서 확정 — 분리하면 "발급은 커밋됐는데 issue_message는
		// 영원히 SENT로 남는" 반쪽 상태가 생겨 Kafka enqueue 성공과 파이프라인 완주를 구분 못 하게 된다.
		markConsumed(event.requestId());

		return couponIssue;
	}

	// Kafka 재전달로 이미 저장된 CouponIssue를 다시 만났을 때(CouponIssueEventConsumer의 스킵 분기)도
	// 동일하게 호출된다 — persist()를 다시 태우지 않고 이 확정만 별도로 재시도할 수 있어야 하기 때문에 public.
	//
	// requestId가 "issue:{recordId}" 형식이 아니면(예: CouponIssueStreamProducer를 직접 호출하는 경로 —
	// 통합 테스트 등) idempotency_key 자체가 없는 요청이므로 조용히 스킵한다. 여기서 예외를 던지면
	// persist()와 같은 트랜잭션에 있는 coupon_issue insert까지 통째로 롤백돼버린다.
	@Transactional
	public void confirmIdempotencySucceeded(CouponIssue couponIssue, String requestId) {
		IdempotencyRequestIdCodec.tryDecode(requestId).ifPresent(recordId -> {
			CustomResponse<CouponIssueCreateResponse> success = CustomResponse.onSuccess(couponIssueConverter.toCreateResponse(couponIssue));
			idempotencyKeyService.succeed(recordId, HttpStatus.OK.value(), objectMapper.writeValueAsString(success));
		});
	}

	// confirmIdempotencySucceeded와 동일한 이유로 public — Kafka 재전달로 이미 저장된 CouponIssue를
	// 다시 만났을 때(CouponIssueEventConsumer의 스킵 분기)도 issue_message 상태 확정이 필요하다.
	// Outbox 저장 시 message_key = requestId로 세팅되므로(IssueMessage.pending) 그 값을 그대로 사용한다.
	@Transactional
	public void markConsumed(String requestId) {
		issueMessageRepository.updateStatusByMessageKey(
			KafkaTopics.COUPON_ISSUE_EVENT, requestId, IssueMessageStatus.CONSUMED
		);
	}
}
