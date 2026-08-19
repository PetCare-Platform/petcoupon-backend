package com.mycom.petcoupon.coupon.redis;

/**
 * Redis 재고 처리를 추상화한 인터페이스.
 * 지금은 MockRedisCouponStockService(메모리 기반)를 쓰고,
 * 담당3의 실제 Redis Lua 연동이 나오면 이 인터페이스만 구현하는 걸로 교체한다
 * (CouponIssueService는 이 인터페이스만 알아서 갈아끼워도 손댈 일 없음).
 */
public interface RedisCouponStockService {
    CouponIssueResult decreaseStock(Long couponId, Long userId, String requestId);
}
