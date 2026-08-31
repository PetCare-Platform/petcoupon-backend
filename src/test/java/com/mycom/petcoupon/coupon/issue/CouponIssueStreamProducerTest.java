package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueStreamProducer;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@ExtendWith(MockitoExtension.class)
class CouponIssueStreamProducerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;
    
    @Mock
    private CouponIssueStreamProperties properties;

    @InjectMocks
    private CouponIssueStreamProducer producer;
    
    @Test
    void 쿠폰_신청_요청을_정상적으로_저장한다() {
    	
    	when(properties.getKey()).thenReturn("coupon:issue:stream");
    	
        RecordId expectedId = RecordId.of("1-0");

        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        when(streamOperations.add(
                ArgumentMatchers
                        .<MapRecord<String, Object, Object>>any()
        )).thenReturn(expectedId);

        RecordId result = producer.publish(1L, 10L, "request-1");

        assertThat(result).isEqualTo(expectedId);


		verify(streamOperations).add(
		        ArgumentMatchers
		                .<MapRecord<String, Object, Object>>any()
		);
    }

    @Test
    void Redis_저장_결과가_null이면_예외가_발생한다() {
    	
    	when(properties.getKey()).thenReturn("coupon:issue:stream");
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        when(streamOperations.add(
                ArgumentMatchers
                        .<MapRecord<String, Object, Object>>any()
        )).thenReturn(null);

        Throwable thrown = catchThrowable(() ->
                producer.publish(1L, 10L, "request-1")
        );

        assertThat(thrown).isInstanceOf(GeneralException.class);

        GeneralException exception = (GeneralException) thrown;

        assertThat(exception.getErrorCode())
                .isEqualTo(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
    }

    @Test
    void Redis_저장_중_예외가_발생하면_공통_예외로_변환한다() {
    	
    	when(properties.getKey()).thenReturn("coupon:issue:stream");
    	
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        when(streamOperations.add(
                ArgumentMatchers
                        .<MapRecord<String, Object, Object>>any()
                        
        )).thenThrow(new DataAccessResourceFailureException("Redis connection failed"));

        Throwable thrown = catchThrowable(() ->
                producer.publish(1L, 10L, "request-1")
        );

        assertThat(thrown).isInstanceOf(GeneralException.class);

        GeneralException exception = (GeneralException) thrown;

        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
    }

    @Test
    void 필수값이_없으면_잘못된_요청_예외가_발생한다() {
    	
        Throwable thrown = catchThrowable(() ->
        		producer.publish(null, 10L, "request-1")
        );

        assertThat(thrown)
                .isInstanceOf(GeneralException.class);

        GeneralException exception = (GeneralException) thrown;

        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_ISSUE_REQUEST);

        verifyNoInteractions(redisTemplate);
    }
}
