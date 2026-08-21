package com.mycom.petcoupon.coupon.issue.config;


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
		StreamMessageListenerContainer<String, MapRecord<String, String, String>> container = StreamMessageListenerContainer.create(connectionFactory, options);

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
			    .cancelOnError(error -> false)
			    .build();

			container.register(request, consumer);

		return container;
	}
	
	// Consumer Group이 존재하지 않으면 생성함 
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
	
	private void handleStreamError(Throwable error) {
		
        log.error("Redis Stream 읽기 오류가 발생했습니다.", error);

        if (hasErrorMessage(error, "NOGROUP")) {
            try {
                ensureConsumerGroup();
                log.info("Redis Stream Consumer Group을 다시 생성했습니다.");
            } catch (Exception recoveryError) {
                log.error(
                    "Redis Stream Consumer Group 재생성에 실패했습니다.",
                    recoveryError
                );
            }
        }
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
