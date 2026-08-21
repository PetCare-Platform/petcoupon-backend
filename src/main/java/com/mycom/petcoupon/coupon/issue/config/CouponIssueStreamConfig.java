package com.mycom.petcoupon.coupon.issue.config;


import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


// Redis Stream Consumer 설정 클래스 
@Slf4j
@Configuration
@RequiredArgsConstructor
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
	
	// Redis 장애 발생 후 Consumer 재시작까지 대기하는 시간 
	private static final long ERROR_RETRY_DELAY_MILLIS = 1_000L;
	
	// 동일한 장애에 대해 복구 작업이 중복 예약되지 않도록 방지
	private final AtomicBoolean recoveryScheduled = new AtomicBoolean(false);
	

	private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
	
	@Bean
	public StreamMessageListenerContainer<String, MapRecord<String, String, String>> couponIssueStreamContainer(
		RedisConnectionFactory connectionFactory,
		CouponIssueStreamConsumer consumer
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
			    .cancelOnError(error -> true)  // 오류 발생 시 현재 요청을 취소하고 Scheduler가 지연 후 Container를 재시작
			    .build();

		this.container.register(request, consumer);

		return this.container;
	}
	
	// 애플리케이션 최초 시작 시 Consumer Group 생성
	private void ensureConsumerGroup() {

		try {
			redisTemplate.opsForStream().createGroup(
				properties.getKey(),
	            ReadOffset.from("0-0"), // Stream의 가장 처음 메시지부터 읽도록 설정 
	            properties.getGroup()
	        );
			
		} catch (Exception e) {
			
			// 이미 Consumer Group이 존재하는 경우 무시
		    if (!hasErrorMessage(e, "BUSYGROUP")) {
		        throw e;
		    }
		}
	}
	
	// Redis 오류로 Consumer Group이 사라진 경우 복구
	private void recreateConsumerGroup() {
	    try {
	        redisTemplate.opsForStream().createGroup(
	            properties.getKey(),
	            ReadOffset.latest(),	// 과거 메시지 전체 재처리를 방지하기 위해 최신 메시지부터 시작
	            properties.getGroup()
	        );
	    } catch (Exception e) {
	        if (!hasErrorMessage(e, "BUSYGROUP")) {
	            throw e;
	        }
	    }
	}
	
	// 오류 발생 시 즉시 재시도하지 않고 Scheduler를 통해 지연 후 재시작
	private void handleStreamError(Throwable error) {
		
        log.error("Redis Stream 읽기 오류가 발생했습니다.", error);

        if (!recoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        
        taskScheduler.schedule(
            () -> {
            	try { 
            		if (hasErrorMessage(error, "NOGROUP")) {
            			recreateConsumerGroup();
                        log.info("Redis Stream Consumer Group을 복구했습니다.");
                    }
            		
            		container.start();
                    log.info("Redis Stream Consumer 재시작 완료");
                    
            	} catch (Exception recoveryError) {
            		log.error(
            			"Redis Stream Consumer 복구에 실패했습니다.",
                        recoveryError
                    );
            	} finally {
            		recoveryScheduled.set(false);
                }
            
            },
            Instant.now().plusMillis(ERROR_RETRY_DELAY_MILLIS)
        );
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
