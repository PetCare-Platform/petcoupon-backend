package com.mycom.petcoupon.coupon.redis;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/**
 * RedisCouponStockService를 CouponIssueLuaService(#33, #54)로 위임하는 어댑터.
 * restoreStock은 재고 롤백용 Lua 스크립트가 아직 없어서 미구현 상태(#58 참고) —
 * 스크립트가 준비되면 CouponIssueLuaService에 rollback 메서드를 추가하고 여기서 위임하도록 채운다.
 *
 * TODO(#60): 박수빈 님의 "Lua 기반 쿠폰 발급 순번 채번" 작업에서 CouponIssueLuaService.issue()의
 * 반환 타입이 CouponIssueLuaResultStatus에서 순번을 포함한 CouponIssueLuaResult로 바뀔 예정.
 * #60이 머지되면 아래 decreaseStock()이 컴파일 깨짐 — 그때 result.status()로 꺼내 쓰도록 고칠 것.
 */
@RequiredArgsConstructor
public class LuaRedisCouponStockService implements RedisCouponStockService {

    private final CouponIssueLuaService couponIssueLuaService;

    @Override
    public CouponIssueResult decreaseStock(Long couponId, Long userId, String requestId) {
        // TODO(#60): 반환 타입이 CouponIssueLuaResult로 바뀌면 이 줄도 같이 바뀜 (클래스 상단 주석 참고)
        CouponIssueLuaResultStatus result = couponIssueLuaService.issue(couponId, userId, requestId);

        return switch (result) {
            case SUCCESS -> CouponIssueResult.SUCCESS;
            case SOLD_OUT -> CouponIssueResult.SOLD_OUT;
            case ALREADY_APPLIED -> CouponIssueResult.DUPLICATE_USER;
            case SAME_REQUEST_RETRY -> CouponIssueResult.DUPLICATE_REQUEST;
            case STOCK_NOT_INITIALIZED -> throw new GeneralException(CouponErrorCode.STOCK_NOT_INITIALIZED);
        };
    }

    @Override
    public void restoreStock(Long couponId, Long userId, String requestId) {
        throw new UnsupportedOperationException("재고 롤백용 Lua 스크립트가 아직 연결되지 않았습니다 (#58)");
    }
}
