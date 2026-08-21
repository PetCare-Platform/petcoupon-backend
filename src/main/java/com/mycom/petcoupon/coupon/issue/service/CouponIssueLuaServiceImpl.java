package com.mycom.petcoupon.coupon.issue.service;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

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
    public CouponIssueLuaResultStatus issue(Long couponId, Long userId) {
    	
    	Long resultCode = redisTemplate.execute(
    		couponIssueLuaScript,
    		List.of(
    			stockKey(couponId), 
    			applicantsKey(couponId)
    		),
    		userId.toString()
    	);

    	if (resultCode == null) {
    		throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
    	}

    	try {
    	    return CouponIssueLuaResultStatus.from(resultCode);
    	    
    	} catch (IllegalArgumentException e) {
    	    throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
    	}
    }
}
