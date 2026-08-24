package com.mycom.petcoupon.coupon.issue.service;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueLuaServiceImpl implements CouponIssueLuaService {

	private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> couponIssueLuaScript;
 

    private String stockKey(Long couponId) {
        return "coupon:issue:stock:{" + couponId + "}";
    }

    private String applicantsKey(Long couponId) {
        return "coupon:issue:applicants:{" + couponId + "}";
    }
    
    @Override
    public CouponIssueLuaResultStatus issue(Long couponId, Long userId, String requestId) {
    	validate(couponId, userId, requestId);
    	
    	Long resultCode;
    	
    	try {
    		resultCode = redisTemplate.execute(
    	    	couponIssueLuaScript,
    	    	List.of(
    	    		stockKey(couponId), 
    	    		applicantsKey(couponId)
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

    	if (resultCode == null) {
    		log.error(
    			"쿠폰 발급 Lua 실행 결과가 null입니다. couponId={}, userId={}, requestId={}",
    			couponId,
    			userId,
    			requestId
    		);
    		throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
    	}

    	try {
    	    return CouponIssueLuaResultStatus.from(resultCode);
    	    
    	} catch (IllegalArgumentException e) {
    		log.error(
    			"알 수 없는 쿠폰 발급 Lua 결과 코드입니다. couponId={}, userId={}, requestId={}, resultCode={}",
    			couponId,
    			userId,
    			requestId,  
    			resultCode,
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
