package com.mycom.petcoupon.coupon.issue.service;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueLuaServiceImpl implements CouponIssueLuaService {

	private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> couponIssueLuaScript;
    private final CouponIssueStreamProperties streamProperties;

    private String stockKey(Long couponId) {
        return "coupon:issue:stock:" + couponId;
    }

    private String applicantsKey(Long couponId) {
        return "coupon:issue:applicants:" + couponId;
    }
    
    @Override
    public CouponIssueLuaResultStatus issue(Long couponId, Long userId, String requestId) {
    	
    	Long resultCode = redisTemplate.execute(
    		couponIssueLuaScript,
    		List.of(
    			stockKey(couponId), 
    			applicantsKey(couponId),
    			streamProperties.getKey()
    		),
    		couponId.toString(),
    		userId.toString(),
    		requestId
    		
    	);

    	if (resultCode == null) {
    		throw new IllegalStateException("쿠폰 발급 Lua Script 실행 결과가 없습니다.");
    	}

        return CouponIssueLuaResultStatus.from(resultCode);
    }
}
