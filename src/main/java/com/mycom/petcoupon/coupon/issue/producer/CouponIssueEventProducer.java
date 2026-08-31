package com.mycom.petcoupon.coupon.issue.producer;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueFailureReason;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventProducer {

	private static final int LAST_ERROR_MAX_LENGTH = 500;
	
	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final IssueMessageRepository issueMessageRepository;
	private final JsonMapper jsonMapper;

	// Executor 타입 빈이 여러 개(redisStreamRecoveryTaskScheduler 등)라 @Qualifier로 특정함.
	// 프로젝트 루트 lombok.config에 등록해둬서 @RequiredArgsConstructor가 이 어노테이션을 생성자로 복사해줌
	@Qualifier("kafkaCallbackExecutor")
	private final Executor kafkaCallbackExecutor;

	// Outbox Publisher(자동 재시도)와 관리자 수동 재처리 양쪽 모두 이 값을 넘겨서 실패할 때마다
	// 여기서 재시도 소진 여부를 판단하므로, 두 호출 경로 모두 소진 시 DLQ 전이가 적용됨
	@Value("${coupon.issue.outbox.max-retry-count:5}")
	private int maxRetryCount;

	public CompletableFuture<Void> publish(IssueMessage issueMessage) {

		try {
			final CouponIssueEvent parsedEvent = jsonMapper.readValue(issueMessage.getPayload(), CouponIssueEvent.class);

			// Kafka 발행 완료 후 수행하는 DB 상태 갱신 작업을
			// Kafka Producer I/O 스레드와 분리하기 위해 별도 Executor에서 처리
			// 파티션 키는 requestId(요청 단위)가 아니라 couponId여야 함 — 같은 쿠폰의 이벤트가
			// 항상 같은 파티션으로 가야 sequenceNo 순서가 컨슈머 처리 순서와 일치함(Kafka는 파티션 내에서만 순서 보장)
			return kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT, String.valueOf(parsedEvent.couponId()), parsedEvent)
				.<Void>handleAsync((result, ex) -> {
					// 콜백 내부 상태 갱신 예외도 Future에 전파하여 Outbox Publisher가 발행 실패로 인식하도록 처리
					try {
						if (ex != null) {
							log.error("[CouponIssueEvent] 발행 실패: requestId={}", parsedEvent.requestId(), ex);
							markFailed(issueMessage, ex);
							
							// Publisher가 발행 실패를 인식하도록 Future를 예외 완료 상태로 만듦
							throw new CompletionException(ex);
						}
						
						log.debug("[CouponIssueEvent] 발행 성공: requestId={}", parsedEvent.requestId());
						markSent(issueMessage);
						return null;
						
					} catch (RuntimeException callbackException) {

						// Kafka 발행 실패를 Publisher에 알리기 위해 의도적으로 던진 예외는 이미 위에서 발행 실패 로그를 남겼으므로 중복 로그를 남기지 않음
						if (callbackException instanceof CompletionException) {
					        throw callbackException;
					    }

						log.error(
							"[CouponIssueEvent] 발행 완료 콜백 처리 중 예외 발생, 수동 확인 필요: messageId={}, requestId={}",
							issueMessage.getMessageId(), parsedEvent.requestId(), callbackException
						);
						
						// 예외를 삼키지 않고 Publisher까지 전달
                        throw callbackException;
					}
				}, kafkaCallbackExecutor);
			
		} catch (RuntimeException e) {
			// payload 파싱 실패 또는 send()가 Future를 반환하기 전 동기 예외가 발생한 경우
			// issue_message를 FAILED 상태로 변경하고 Publisher에 실패를 전파
			log.error("[CouponIssueEvent] 발행 처리 실패: messageId={}", issueMessage.getMessageId(), e);
			
			markFailed(issueMessage, e);
			
			// Publisher의 exceptionally()에서 실패를 처리할 수 있도록 반환
			return CompletableFuture.failedFuture(e);
		}
	}

	private void markSent(IssueMessage issueMessage) {
		issueMessageRepository.markSent(issueMessage.getMessageId(), IssueMessageStatus.SENT, LocalDateTime.now());
	}

	private void markFailed(IssueMessage issueMessage, Throwable ex) {
		// [#217] claimForReprocess가 이제 status를 DLQ -> REPROCESSING으로 전이시키며 선점하므로,
		// 이 메시지가 DLQ 수동 재처리 중이었다면 여기서 issueMessage.getStatus()는 REPROCESSING임.
		// retryCount 소진 여부와 무관하게 무조건 DLQ로 복귀시켜, 재처리 실패가 FAILED로 새서
		// Outbox Poller의 자동 재시도 대상(PENDING/FAILED)에 다시 걸리는 걸 막음
		boolean fromDlqReprocess = issueMessage.getStatus() == IssueMessageStatus.REPROCESSING;
		boolean retryExhausted = issueMessage.getRetryCount() + 1 >= maxRetryCount;
		boolean shouldGoToDlq = fromDlqReprocess || retryExhausted;
		IssueMessageStatus status = shouldGoToDlq ? IssueMessageStatus.DLQ : IssueMessageStatus.FAILED;

		// [PR 리뷰 반영] 같은 메시지의 발행이 겹쳐(Outbox poller 자동 재시도 vs 관리자 DLQ 재처리)
		// 한쪽이 먼저 성공해 SENT/CONSUMED로 확정된 뒤 다른 쪽의 지연된 실패 콜백이 이 메서드를
		// 타면, 조건 없는 UPDATE는 그 성공 상태를 FAILED/DLQ로 되돌릴 수 있다. 이 실패 콜백이
		// "어느 발행 시도"에서 온 것인지(fromDlqReprocess)에 맞는 시작 상태에서만 갱신되게 좁힌다 —
		// 다른 시도가 먼저 끝냈다면 0건 갱신으로 조용히 무시된다(markSent와 동일한 패턴).
		Collection<IssueMessageStatus> expectedStatuses = fromDlqReprocess
			? List.of(IssueMessageStatus.REPROCESSING)
			: List.of(IssueMessageStatus.PENDING, IssueMessageStatus.FAILED);

		issueMessageRepository.markPublishFailed(
			issueMessage.getMessageId(), status, errorMessage(ex), IssueFailureReason.KAFKA_PUBLISH_FAILED, expectedStatuses
		);

		if (shouldGoToDlq) {
			// 관리자 수동 재처리(claimForReprocess) 경로에서는 issueMessage의 retryCount가
			// 이미 DB에서 별도로 증가된 뒤라 메모리 값과 어긋날 수 있어, 로그엔 실제 카운트를 남기지 않음
			log.error(
				"[CouponIssueEvent] Outbox 발행 재시도 소진 또는 DLQ 재처리 실패, DLQ 전이: messageId={}",
				issueMessage.getMessageId()
			);
		}
	}

	// DLQ 전이 자체는 재고를 건드리지 않는다 — 재발행(reprocess)으로 아직 성공할 여지가 있는 메시지의
	// 재고를 성급히 복구하면 이후 재발행이 성공했을 때 초과 발급으로 이어질 수 있다. 재고 복구는 관리자가
	// CouponIssueDlqAdminController의 abandon API로 "포기"를 명시적으로 선택했을 때만 실행된다.

	private String errorMessage(Throwable throwable) {
		if (throwable == null) {
			return "Unknown error";
		}

		Throwable rootCause = throwable;
		while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
			rootCause = rootCause.getCause();
		}

		String topMessage = throwable.getMessage();
		String rootMessage = rootCause.getMessage();

		String result;
		if (rootCause == throwable || rootMessage == null || rootMessage.isBlank()) {
			result = (topMessage != null && !topMessage.isBlank())
					? topMessage
					: throwable.getClass().getSimpleName();
		} else if (topMessage == null || topMessage.isBlank() || topMessage.equals(rootMessage)) {
			result = String.format("%s: %s", rootCause.getClass().getSimpleName(), rootMessage);
		} else {
			result = String.format("%s: %s (Caused by: %s: %s)",
					throwable.getClass().getSimpleName(),
					topMessage,
					rootCause.getClass().getSimpleName(),
					rootMessage);
		}

		if (result.length() <= LAST_ERROR_MAX_LENGTH) {
			return result;
		}

		return result.substring(0, LAST_ERROR_MAX_LENGTH);
	}
}
