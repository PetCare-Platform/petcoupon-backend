package com.mycom.petcoupon.coupon.issue.producer;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
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

	public CompletableFuture<Void> publish(IssueMessage issueMessage) {

		try {
			final CouponIssueEvent parsedEvent = jsonMapper.readValue(issueMessage.getPayload(), CouponIssueEvent.class);

			// Kafka 발행 완료 후 수행하는 DB 상태 갱신 작업을
			// Kafka Producer I/O 스레드와 분리하기 위해 별도 Executor에서 처리
			return kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT, parsedEvent.requestId(), parsedEvent)
				.<Void>handleAsync((result, ex) -> {
					// 콜백 내부 상태 갱신 예외도 Future에 전파하여 Outbox Publisher가 발행 실패로 인식하도록 처리
					try {
						if (ex != null) {
							log.error("[CouponIssueEvent] 발행 실패: requestId={}", parsedEvent.requestId(), ex);
							markFailed(issueMessage, ex);
							
							// Publisher가 발행 실패를 인식하도록 Future를 예외 완료 상태로 만듦
							throw new CompletionException(ex);
						}
						
						log.info("[CouponIssueEvent] 발행 성공: requestId={}", parsedEvent.requestId());
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
		issueMessageRepository.markPublishFailed(issueMessage.getMessageId(), IssueMessageStatus.FAILED, errorMessage(ex));
	}

	// TODO: 최대 재시도 초과 Outbox 메시지의 DLQ 처리 및 Redis 재고 보상/정합성 보정 정책 구현
	
	private String errorMessage(Throwable throwable) {
	    String message = throwable.getMessage();

	    if (message == null || message.isBlank()) {
	        message = throwable.getClass().getSimpleName();
	    }

	    if (message.length() <= LAST_ERROR_MAX_LENGTH) {
	        return message;
	    }

	    return message.substring(0, LAST_ERROR_MAX_LENGTH);
	}
}
