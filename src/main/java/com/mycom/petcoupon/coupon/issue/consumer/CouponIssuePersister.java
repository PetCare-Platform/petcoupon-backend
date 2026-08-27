package com.mycom.petcoupon.coupon.issue.consumer;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
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
		// @Modifying(clearAutomatically = true)라 영속성 컨텍스트를 통째로 비우는데, 그 뒤 프록시의
		// 필드를 읽으려 하면 컨텍스트가 이미 비워져 있어 LazyInitializationException이 발생한다
		// (실측 확인함, 단일 요청에서도 항상 재현됨). findById로 미리 로딩해두면 이미 메모리에 있는
		// 값이라 안전하다. coupon도 같은 이유로 findById를 쓴다 — 지금은 id만 읽어서 문제없지만
		// (예: CouponIssueConverter.toRequestResponse는 name도 읽음) 응답 DTO가 coupon의 다른
		// 필드를 노출하도록 바뀌면 재발할 수 있다.
		AppUser user = appUserRepository.findById(event.userId())
			.orElseThrow(() -> new IllegalStateException(
				"발급 대상 사용자를 찾을 수 없음: userId=" + event.userId() + ", requestId=" + event.requestId()
			));

		Coupon coupon = couponRepository.findById(event.couponId())
			.orElseThrow(() -> new IllegalStateException(
				"발급 대상 쿠폰을 찾을 수 없음: couponId=" + event.couponId() + ", requestId=" + event.requestId()
			));

		CouponIssue couponIssue = couponIssueRepository.saveAndFlush(
			CouponIssue.builder()
				.coupon(coupon)
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

		return couponIssue;
	}

	// 스펙 아키텍처(Kafka Consumer → DB confirmation → Notification Mock)의 Mock 알림 기록.
	// persist()와 별도 트랜잭션 — 같은 트랜잭션에 묶으면 phone null 등으로 알림 저장이 실패할 때
	// 이미 확정됐어야 할 발급까지 롤백되고 Kafka 재시도가 무한 반복된다.
	//
	// 이 메서드 안에서 실패를 삼키지 않는다(예전엔 try/catch로 삼켰으나 실측으로 틀렸다고 확인함) —
	// notificationLogRepository.save()가 실패하면 그 시점에 JPA 스펙상 트랜잭션이 이미
	// rollback-only로 마킹된다. 메서드 안에서 그 예외를 잡아 삼켜도 메서드는 "정상 반환"한 것으로
	// 처리되고, 그 직후 @Transactional 프록시가 커밋을 시도하다가(메서드 몸통 *바깥*, 이 코드로는
	// 손댈 수 없는 지점) rollback-only 트랜잭션을 커밋하려 한 걸 감지해 UnexpectedRollbackException을
	// 새로 던진다 — 결국 호출부에는 무조건 예외가 전파된다(CouponIssuePersisterIntegrationTest의
	// phone이_없어도_발급은_정상_처리되고_알림_기록만_실패하며_예외가_전파되지_않는다()로 실측 확인).
	// 그래서 실패 흡수는 이 메서드를 호출하는 쪽(CouponIssueEventConsumer)의 try/catch가 유일하게
	// 가능한 지점이다 — 여기서 다시 삼키려고 하면 원인 예외만 가려질 뿐 근본적으로 못 막는다.
	//
	// 재전달로 이미 저장된 건(Consumer의 스킵 분기)에서는 호출 안 함 — uk_noti_issue_channel
	// 유니크 제약도 있고, 발급이 처음 일어난 시점에만 알림이 나가야 하기 때문이다.
	//
	// TODO(#119): recipientMasked는 마스킹 담당자가 별도 처리 예정 — 지금은 원본 전화번호 그대로.
	@Transactional
	public void recordNotification(CouponIssue couponIssue) {
		AppUser user = couponIssue.getUser();
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
