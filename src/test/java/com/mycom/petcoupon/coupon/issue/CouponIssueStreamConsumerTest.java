package com.mycom.petcoupon.coupon.issue;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueStreamConsumer;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.messaging.service.CouponIssueOutboxService;

@ExtendWith(MockitoExtension.class)
public class CouponIssueStreamConsumerTest {
	
	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private StreamOperations<String, Object, Object> streamOperations;

	@Mock
	private CouponIssueStreamProperties properties;

	@Mock
    private CouponIssueLuaService couponIssueLuaService;

    @Mock
    private CouponIssueOutboxService couponIssueOutboxService;

	private CouponIssueStreamConsumer consumer;

	@BeforeEach
	void setUp() {
		consumer = new CouponIssueStreamConsumer(redisTemplate, properties, couponIssueLuaService, couponIssueOutboxService);
	}

	@Test
	void 정상적인_메시지를_처리하면_Outbox를_저장하고_ACK한다() {
		
		when(couponIssueLuaService.issue(1L, 100L, "request-1"))
			.thenReturn(
	            new CouponIssueLuaResult(CouponIssueLuaResultStatus.SUCCESS,  1L)
	    );
		
		when(redisTemplate.opsForStream()).thenReturn(streamOperations);
		when(properties.getKey()).thenReturn("coupon:issue:stream");
		when(properties.getGroup()).thenReturn("coupon-issue-group");
		
		MapRecord<String, String, String> message = MapRecord.create(
			"coupon:issue:stream",
			Map.of(
				"requestId", "request-1",
				"couponId", "1",
				"userId", "100"
			)
		);

		consumer.onMessage(message);

		verify(couponIssueLuaService).issue(
			1L,   
			100L,
			"request-1"
	    );
		
		verify(couponIssueOutboxService).saveIfAbsent(
			1L,
			100L,
			"request-1",
			1L
		);
		
		verify(streamOperations).acknowledge(
			"coupon:issue:stream",
			"coupon-issue-group",
			message.getId()
		);
	}

	@Test
	void 메시지_필드가_잘못되면_ACK하지_않는다() {
		
		MapRecord<String, String, String> message = MapRecord.create(
			"coupon:issue:stream",
			Map.of(
				"requestId", "request-1",
				"couponId", "invalid",
				"userId", "100"
			)
		);

		consumer.onMessage(message);
		
		verifyNoInteractions(couponIssueLuaService);
        verifyNoInteractions(couponIssueOutboxService);
		verifyNoInteractions(redisTemplate);
	}
}
