package com.mycom.petcoupon.coupon.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;

/**
 * Entity <-> DTO 변환 전담 클래스.
 * 지금은 저장하는 Entity가 없어서 원시값만 조립한다.
 * 나중에 CouponIssue 엔티티가 생기면 toCreateResponse(CouponIssue)로 바꾸면 됨.
 */
@Component
public class CouponIssueConverter {

    public CouponIssueCreateResponse toCreateResponse(Long couponId, Long userId) {
        return CouponIssueCreateResponse.builder()
                .couponId(couponId)
                .userId(userId)
                .build();   
       
    }
}
