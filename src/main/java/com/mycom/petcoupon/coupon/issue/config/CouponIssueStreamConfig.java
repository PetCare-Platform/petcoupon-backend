package com.mycom.petcoupon.coupon.issue.config;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

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
	private final TaskScheduler taskScheduler;
	private final CouponIssueStreamConsumer consumer;

	// Redis 오류 발생 후 복구 시도까지의 지연 시간
	private static final long ERROR_RETRY_DELAY_MILLIS = 1_000L;

	// 동일한 장애에 대해 복구 작업이 중복 예약되지 않도록 방지
	private final AtomicBoolean recoveryScheduled = new AtomicBoolean(false);

	private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

	// TaskScheduler 빈이 여러 개(couponExpireBatchTaskScheduler 등)라
	// @Qualifier로 Redis Stream 복구 전용 스케줄러를 명시해야 함 — Lombok의
	// @RequiredArgsConstructor는 필드의 @Qualifier를 생성자 파라미터로 안 옮겨주므로 직접 작성함
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

        container.register(request, consumer);
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

	    if (!recoveryScheduled.compareAndSet(false, true)) {
	        return;
	    }

	    taskScheduler.schedule(
	        () -> {
	        	try {
	        		recoverConsumer(error);
	        		log.info("Redis Stream Consumer 재시작 완료");
	                
	        	} catch (Exception recoveryError) {
	        		recoveryScheduled.set(false);
	        		
	        		log.error(
	        			"Redis Stream Consumer 복구에 실패했습니다.",
	        			recoveryError
	        		);
	        		
	        		// TODO(#26, #47): 복구 실패 시 지수 백오프 재시도 및 장애 복구 통합 테스트 추가
	        		
	        	} 
	        },
	        
	        Instant.now().plusMillis(ERROR_RETRY_DELAY_MILLIS)
	    );
	}

	private void recoverConsumer(Throwable error) {
		
		 // 취소된 Subscription을 다시 시작할 수 있도록 Container 상태를 초기화
	    container.stop();

	    if (hasErrorMessage(error, "NOGROUP")) {
	        ensureConsumerGroup();
	        log.info("Redis Stream Consumer Group을 복구했습니다.");
	    }
	    
	    // 재시작 직후 발생하는 오류도 다음 복구 작업을 예약할 수 있도록 해제
	    recoveryScheduled.set(false);
	    
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
