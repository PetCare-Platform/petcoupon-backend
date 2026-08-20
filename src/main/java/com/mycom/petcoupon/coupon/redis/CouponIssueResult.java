package com.mycom.petcoupon.coupon.redis;

/**
 * Redis 재고 차감 시도 결과.
 * 실제 Redis Lua 스크립트 반환값(1/-1/-2/-3/-4)을 이 enum으로 옮겨서 쓴다고 보면 됨.
 */
public enum CouponIssueResult {
    SUCCESS,           // 재고 차감 성공
    SOLD_OUT,          // 재고 소진
    DUPLICATE_USER,    // 같은 유저가 같은 쿠폰 이미 신청함 (1인 1매 위반)
    DUPLICATE_REQUEST  // 같은 requestId로 재전송됨 (네트워크 재시도 등)
}
