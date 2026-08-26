package com.mycom.petcoupon.coupon.issue.consumer;

import java.time.LocalDateTime;

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
import com.mycom.petcoupon.notification.entity.NotificationLog;
import com.mycom.petcoupon.notification.entity.enums.Channel;
import com.mycom.petcoupon.notification.entity.enums.NotificationStatus;
import com.mycom.petcoupon.notification.repository.NotificationLogRepository;
import com.mycom.petcoupon.user.entity.AppUser;
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
	private final NotificationLogRepository notificationLogRepository;

	@Transactional
	public CouponIssue persist(CouponIssueEvent event) {
		// getReferenceById(지연 프록시)를 쓰면 안 된다 — increaseIssuedQuantity()가
		// @Modifying(clearAutomatically = true)라 영속성 컨텍스트를 통째로 비우는데, 그 뒤
		// recordNotification()에서 user.getPhone()으로 프록시를 초기화하려 하면 컨텍스트가
		// 이미 비워져 있어 LazyInitializationException이 발생한다(실측 확인함, 동시성과 무관하게
		// 단일 요청에서도 항상 재현됨). findById로 미리 실제 값을 로딩해두면 컨텍스트가 비워져도
		// 이미 메모리에 있는 필드값이라 안전하다.
		AppUser user = appUserRepository.findById(event.userId())
			.orElseThrow(() -> new IllegalStateException(
				"발급 대상 사용자를 찾을 수 없음: userId=" + event.userId() + ", requestId=" + event.requestId()
			));

		CouponIssue couponIssue = couponIssueRepository.saveAndFlush(
			CouponIssue.builder()
				.coupon(couponRepository.getReferenceById(event.couponId()))
				.user(user)
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

		// 스펙 아키텍처(Kafka Consumer → DB confirmation → Notification Mock)의 Mock 알림 기록.
		// 재전달로 이미 저장된 건(Consumer의 스킵 분기)에서는 호출 안 함 — uk_noti_issue_channel
		// 유니크 제약도 있고, 애초에 발급이 실제로 처음 일어난 시점에만 알림이 나가야 하기 때문에
		// confirmIdempotencySucceeded/markConsumed와 달리 persist() 안에서만 호출되는 private 메서드다.
		recordNotification(couponIssue, user);

		return couponIssue;
	}

	// TODO(#119): recipientMasked는 개인정보 마스킹 담당자가 별도로 처리 예정 — 지금은 마스킹 전
	// 원본 전화번호를 그대로 넣어둠. 마스킹 로직이 준비되면 이 자리를 그걸로 교체할 것.
	private void recordNotification(CouponIssue couponIssue, AppUser user) {
		notificationLogRepository.save(
			NotificationLog.builder()
				.couponIssue(couponIssue)
				.user(user)
				.channel(Channel.SMS)
				.recipientMasked(user.getPhone())
				.content("[PetCoupon] 쿠폰이 발급되었습니다. 쿠폰코드: " + couponIssue.getCouponCode())
				.status(NotificationStatus.SENT)
				.sentAt(LocalDateTime.now())
				.build()
		);
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
