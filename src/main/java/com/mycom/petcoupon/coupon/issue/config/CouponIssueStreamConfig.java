package com.mycom.petcoupon.coupon.issue.config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.TaskScheduler;

import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueStreamConsumer;

import lombok.extern.slf4j.Slf4j;


// Redis Stream Consumer 설정 클래스
@Slf4j
@Configuration
@ConditionalOnProperty(
	prefix = "coupon.issue.stream",
	name = "enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class CouponIssueStreamConfig {

	private final CouponIssueStreamProperties properties;
	private final StringRedisTemplate redisTemplate;
	
	@Qualifier("redisStreamRecoveryTaskScheduler")
	private final TaskScheduler taskScheduler;
	
	private final CouponIssueStreamConsumer consumer;

	// 동일한 장애에 대해 복구 작업이 중복 예약되지 않도록 방지
	private final AtomicBoolean recoveryScheduled = new AtomicBoolean(false);
	
	// Consumer가 정상 메시지를 다시 처리할 때까지 유지하는 연속 복구 실패 횟수
	private final AtomicInteger recoveryFailedAttempts = new AtomicInteger(0);

	private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

	// TaskScheduler 빈이 여러 개(couponExpireBatchTaskScheduler 등)라
	// @Qualifier로 Redis Stream 복구 전용 스케줄러를 명시해야 함
	public CouponIssueStreamConfig(
			CouponIssueStreamProperties properties,
			StringRedisTemplate redisTemplate,
			@Qualifier("redisStreamRecoveryTaskScheduler") TaskScheduler taskScheduler,
			CouponIssueStreamConsumer consumer
	) {
		this.properties = properties;
		this.redisTemplate = redisTemplate;
		this.taskScheduler = taskScheduler;
		this.consumer = consumer;
	}
	
	@Bean
	public StreamMessageListenerContainer<String, MapRecord<String, String, String>> couponIssueStreamContainer(
		RedisConnectionFactory connectionFactory
	) {
		ensureConsumerGroup();

		// Redis Stream 을 계속 감시하는 Listener Container 설정 
		StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
			StreamMessageListenerContainer.StreamMessageListenerContainerOptions
				.builder()
				.batchSize(10)
				.build();

		
		// Container 생성 
		this.container = StreamMessageListenerContainer.create(connectionFactory, options);

		registerConsumerRequest();

		return this.container;
	}
	
	private void registerConsumerRequest() {
        StreamMessageListenerContainer.ConsumerStreamReadRequest<String> request =
            StreamMessageListenerContainer.StreamReadRequest
                .builder(
                    StreamOffset.create(
                        properties.getKey(),
                        ReadOffset.lastConsumed()
                    )
                )
                .consumer(
                    Consumer.from(
                        properties.getGroup(),
                        properties.getConsumer()
                    )
                )
                .autoAcknowledge(false)
                .errorHandler(this::handleStreamError)
                .cancelOnError(error -> true)
                .build();

        container.register(request, message -> {
        	// 메시지를 읽었다는 것으로 Redis 연결 복구는 확인됐다.
            recoveryFailedAttempts.set(0);

            consumer.onMessage(message);
        });
    }
	
	// Consumer Group이 없으면 생성하고, 이미 존재하면 기존 그룹을 사용
	private void ensureConsumerGroup() {

		try {
			redisTemplate.opsForStream().createGroup(
				properties.getKey(),
				
				// 그룹 유실 시 메시지 누락을 방지하기 위해 처음부터 복구
				// 전체 재전달이 가능하므로 실제 발급 로직은 멱등성을 보장해야 함 
	            ReadOffset.from("0-0"), 
	            properties.getGroup()
	        );
			
		} catch (Exception e) {
			
			// 이미 존재하는 그룹은 그대로 사용
		    if (!hasErrorMessage(e, "BUSYGROUP")) {
		        throw e;
		    }
		}
	}
	
	// 읽기 오류 발생 시 중복 예약을 방지하고 일정 시간 후 복구
	private void handleStreamError(Throwable error) {
	    log.error("Redis Stream 읽기 오류가 발생했습니다.", error);

	    // 이미 복구 재시도 체인이 실행 중이면 중복 예약하지 않음 
	    if (!recoveryScheduled.compareAndSet(false, true)) {
	        return;
	    }

	    // 첫 오류면 0(1초), 이후 연속 오류면 누적 횟수에 맞는 지연 시간을 사용한다.
	    int failedAttempts = recoveryFailedAttempts.getAndIncrement();
	    
	    scheduleRecovery(error, failedAttempts);
	}
	
	/**
	 * 복구 실패 횟수에 따라 지수 백오프를 적용해 다음 복구 작업을 예약한다.
	 *
	 * failedAttempts=0 -> 1초
	 * failedAttempts=1 -> 2초
	 * failedAttempts=2 -> 4초
	 * ...
	 * 최대 30초
	 */
	private void scheduleRecovery(Throwable cause, int failedAttempts) {
	    Duration delay = calculateRecoveryDelay(failedAttempts);

	    taskScheduler.schedule(
	        () -> {
	            try {
	                recoverConsumer(cause);

	                // start()가 호출되었으므로 다음 오류는 다시 복구 작업을 예약할 수 있게 한다.
	                // 단, recoveryFailedAttempts는 실제 메시지 소비가 성공할 때까지 초기화하지 않는다.
	                recoveryScheduled.set(false);

	                log.info(
	                    "Redis Stream Consumer 재시작을 요청했습니다. failedAttempts={}, delay={}",
	                    failedAttempts,
	                    delay
	                );

	            } catch (Exception recoveryError) {
	                Duration nextDelay = calculateRecoveryDelay(failedAttempts + 1);

	                log.error(
	                    "Redis Stream Consumer 복구에 실패했습니다. 다음 재시도를 예약합니다. "
	                        + "failedAttempts={}, nextDelay={}",
	                    failedAttempts + 1,
	                    nextDelay,
	                    recoveryError
	                );

	                // recoveryScheduled는 true인 채로 유지한다.
	                // 그래서 읽기 오류가 여러 번 발생해도 중복 재시도 작업이 생기지 않는다.
	                int nextFailedAttempts = failedAttempts + 1;

	                // 다음 오류가 비동기로 발생해도 그다음 백오프 횟수부터 이어지도록 한다.
	                recoveryFailedAttempts.accumulateAndGet(
	                	nextFailedAttempts + 1,
	                	Math::max
	                );

	                scheduleRecovery(recoveryError, nextFailedAttempts);
	            }
	        },
	        Instant.now().plus(delay)
	    );
	}

	/**
	 * initialDelay * multiplier^failedAttempts 값을 계산하되,
	 * maxDelay를 넘으면 maxDelay로 고정한다.
	 */
	private Duration calculateRecoveryDelay(int failedAttempts) {
	    CouponIssueStreamProperties.Recovery recovery = properties.getRecovery();

	    Duration delay = recovery.getInitialDelay();
	    Duration maxDelay = recovery.getMaxDelay();

	    for (int i = 0; i < failedAttempts && delay.compareTo(maxDelay) < 0; i++) {
	        try {
	            delay = delay.multipliedBy(recovery.getMultiplier());
	        } catch (ArithmeticException e) {
	            return maxDelay;
	        }

	        if (delay.compareTo(maxDelay) >= 0) {
	            return maxDelay;
	        }
	    }

	    return delay;
	}
	
	private void recoverConsumer(Throwable error) {
		
		 // 취소된 Subscription을 다시 시작할 수 있도록 Container 상태를 초기화
	    container.stop();

	    if (hasErrorMessage(error, "NOGROUP")) {
	        ensureConsumerGroup();
	        log.info("Redis Stream Consumer Group을 복구했습니다.");
	    }
	    
	    container.start();
	}
		
	private boolean hasErrorMessage(Throwable throwable, String targetMessage) {
        Throwable cause = throwable;

        while (cause != null) {
            String message = cause.getMessage();

            if (message != null && message.contains(targetMessage)) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}
