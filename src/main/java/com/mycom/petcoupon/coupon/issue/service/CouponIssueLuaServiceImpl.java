package com.mycom.petcoupon.coupon.issue.service;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueLuaServiceImpl implements CouponIssueLuaService {

	private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> couponIssueLuaScript;
 

    private String stockKey(Long couponId) {
        return "coupon:issue:stock:{" + couponId + "}";
    }

    private String applicantsKey(Long couponId) {
        return "coupon:issue:applicants:{" + couponId + "}";
    }
    
    private String sequenceKey(Long couponId) {
        return "coupon:issue:sequence:{" + couponId + "}";
    }

    private String requestSequenceKey(Long couponId) {
        return "coupon:issue:request-sequence:{" + couponId + "}";
    }
    
    @Override
    public CouponIssueLuaResult issue(Long couponId, Long userId, String requestId) {
    	validate(couponId, userId, requestId);
    	
    	List<?> luaResult;
    	
    	try {
    		luaResult = redisTemplate.execute(
    	    	couponIssueLuaScript,
    	    	List.of(
    	    		stockKey(couponId), 
    	    		applicantsKey(couponId),
    	    		sequenceKey(couponId),
    	    		requestSequenceKey(couponId)
    	    	),
    	    	userId.toString(),
    	    	requestId
    	    );
    	} catch (DataAccessException e) {
    		log.error(
    			"쿠폰 발급 Lua 실행 중 Redis 접근에 실패했습니다. couponId={}, userId={}, requestId={}",
    			couponId, userId, requestId, e
    		);
    		throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
		}

    	if (luaResult == null || luaResult.size() != 2) {
    		log.error(
    				"유효하지 않은 쿠폰 발급 Lua 실행 결과입니다. couponId={}, userId={}, requestId={}, result={}",
    			couponId,
    			userId,
    			requestId,
    			luaResult
    		);
    		throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
    	}

    	try {
    		long resultCode = ((Number) luaResult.get(0)).longValue();
            long sequenceNo = ((Number) luaResult.get(1)).longValue();
            
            CouponIssueLuaResultStatus status = CouponIssueLuaResultStatus.from(resultCode);
    	    
            Long issuedSequenceNo = null;
            
            if (status == CouponIssueLuaResultStatus.SUCCESS
                    || status == CouponIssueLuaResultStatus.SAME_REQUEST_RETRY) {
            	
            	if(sequenceNo <= 0) {
            		throw new IllegalArgumentException("성공 또는 재시도 결과에 유효한 순번이 없습니다. sequenceNo=" + sequenceNo);
            	}
            	
            	issuedSequenceNo = sequenceNo;
            }
            
            return CouponIssueLuaResult.builder()
	            		.status(status)
	            		.sequenceNo(issuedSequenceNo)
	            		.build();
            
    	} catch (IllegalArgumentException | ClassCastException e) {
    		log.error(
    			"알 수 없는 쿠폰 발급 Lua 결과입니다. couponId={}, userId={}, requestId={}, result={}",
    			couponId,
    			userId,
    			requestId,  
    			luaResult,
    			e
    		);
    	    throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
    	}
    }
    
    private void validate(Long couponId, Long userId, String requestId) {
        if (couponId == null || couponId <= 0
                || userId == null || userId <= 0
                || requestId == null || requestId.isBlank()) {

            throw new GeneralException(CouponErrorCode.INVALID_ISSUE_REQUEST);
        }
    }
}
