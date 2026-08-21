package com.mycom.petcoupon.coupon.issue.dto.enums;

public enum CouponIssueLuaResultStatus {
	
	SUCCESS,
    ALREADY_APPLIED,
    SOLD_OUT;

	public static CouponIssueLuaResultStatus from(long code) {
	    return switch ((int) code) {
	        case 1 -> SUCCESS;
	        case 2 -> ALREADY_APPLIED;
	        case 3 -> SOLD_OUT;
	        default -> throw new IllegalArgumentException(
	            "알 수 없는 쿠폰 발급 Lua 결과 코드입니다. code=" + code
	        );
	    };
	}
}
