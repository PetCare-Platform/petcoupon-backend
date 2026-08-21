package com.mycom.petcoupon.coupon.issue.dto.enums;

public enum CouponIssueLuaResultStatus {
	
	SUCCESS(1L),
    ALREADY_APPLIED(2L),
    SOLD_OUT(3L);

    private final long code;

    CouponIssueLuaResultStatus(long code) {
        this.code = code;
    }

    public static CouponIssueLuaResultStatus from(long code) {
        for (CouponIssueLuaResultStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }

        throw new IllegalArgumentException(
            "알 수 없는 쿠폰 발급 Lua 결과 코드입니다. code=" + code
        );
    }
}
