package com.mycom.petcoupon.coupon.redis;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

/**
 * RedisCouponStockService의 임시 구현체.
 * 실제 Redis Lua 스크립트 없이 메모리(ConcurrentHashMap)로 재고·중복 판정을 흉내낸다.
 * 서버 재시작하면 상태가 초기화되는데, mock 단계라 정상 동작이다.
 * 담당3 실제 Redis 연동 나오면 이 클래스 통째로 교체하면 됨(인터페이스는 안 바뀜).
 */
@Service // 빠지면 CouponIssueService에 주입할 빈이 없어서 부팅 자체가 안 됨
public class MockRedisCouponStockService implements RedisCouponStockService {

    private static final int DEFAULT_STOCK = 5;

    // couponId -> 남은 재고 수
    private final ConcurrentHashMap<Long, AtomicInteger> stockByCoupon = new ConcurrentHashMap<>();
    // "couponId:userId" 조합으로 이미 발급된 유저를 추적 (1인 1매 방어)
    private final Set<String> issueUserKeys = ConcurrentHashMap.newKeySet();
    // 이미 처리한 requestId 추적 (같은 요청 재시도 방어)
    private final Set<String> seenRequestIds = ConcurrentHashMap.newKeySet();


    @Override
    public CouponIssueResult decreaseStock(Long couponId, Long userId, String requestId)  {

        // 1. 재시도(같은 requestId) 체크가 제일 먼저 — 재고를 두 번 건드리기 전에 걸러야 함
        if(!seenRequestIds.add(requestId)) {
            return CouponIssueResult.DUPLICATE_REQUEST;

        }

        // 2. 1인 1매 체크
        String userKey = couponId + ":" + userId;

        if(!issueUserKeys.add(userKey)) {
            return CouponIssueResult.DUPLICATE_USER;
        }

        // 3. 재고 원자적 차감. computeIfAbsent로 처음 보는 couponId면 기본 재고로 초기화
        AtomicInteger stock = stockByCoupon.computeIfAbsent(couponId, id -> new AtomicInteger(DEFAULT_STOCK));
        int remaining = stock.decrementAndGet();

        if(remaining < 0) {
            stock.incrementAndGet(); // 원복 — 실제로는 차감 안 된 것처럼 되돌림
            issueUserKeys.remove(userKey); // 실패했으니 중복 체크에서 제외 (재시도 가능하게)
            return CouponIssueResult.SOLD_OUT;
        }

        return CouponIssueResult.SUCCESS;
    }

}
