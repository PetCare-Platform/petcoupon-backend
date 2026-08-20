package com.mycom.petcoupon.coupon.issue.producer;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueStreamProducer {

	// Redis Stream 이름 설정 
	private static final String STREAM_KEY = "coupon:issue:stream";
	
	private final StringRedisTemplate redisTemplate;
	
	// 쿠폰 신청 요청을 Redis Stream 에 저장하고, 저장된 메시지 ID를 반환 
	public RecordId publish(Long couponId, Long userId, String requestId) {
		
		/*
		 * 현재는 Producer가 독립적으로 호출될 수 있으므로 Redis Stream 저장 전 필수 값을 한 번 더 검증함 
		 * 추후 CouponIssueService와 연결된 이후에도 Service에서 1차 검증 후 Producer에서 2차 방어 검증을 수행
		 */
		validate(couponId, userId, requestId);
		
		// DTO -> Redis Stream 메시지 형태로 변환 
		Map<String, String> message = Map.of(
				"requestId", requestId,
				"couponId", String.valueOf(couponId),
				"userId", String.valueOf(userId)
		);
		
		// Redis Stream 에 저장할 메시지 객체 생성 
		MapRecord<String, String, String> record = StreamRecords
				.newRecord()
				.in(STREAM_KEY)
				.ofMap(message);

		try {
			// Redis Stream 저장 
			RecordId recordId = redisTemplate.opsForStream().add(record);

			if (recordId == null) {
				throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
			}
			
			return recordId;
			
		} catch (DataAccessException e) {
			log.error(
		            "Redis Stream 저장 실패. couponId={}, userId={}, requestId={}",
		            couponId,
		            userId,
		            requestId,
		            e
		    );
			
			throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
		}
	}
	
	private void validate(Long couponId, Long userId,String requestId) {
		if (couponId == null || couponId <= 0 
				|| userId == null || userId <= 0 
				|| requestId == null || requestId.isBlank()) {
			
			throw new GeneralException(CouponErrorCode.INVALID_ISSUE_REQUEST);
	    }
	}
}	
