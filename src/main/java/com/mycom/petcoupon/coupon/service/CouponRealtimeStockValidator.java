package com.mycom.petcoupon.coupon.service;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.global.common.exception.GeneralException;

/**
 * Redis가 관리하는 실시간 쿠폰 재고의 공통 유효성 검증기.
 *
 * 실시간 현황과 부하 테스트 현황이 같은 Redis 값을 서로 다르게 판단하지 않도록
 * 검증 규칙을 한곳에서 관리한다. 재고 키가 아직 초기화되지 않은 상태는 정상으로
 * 취급하고, 초기화된 재고만 전체 수량 범위 안에 있는지 확인한다.
 */
@Component
public class CouponRealtimeStockValidator {

    public void validate(CouponIssueRealtimeStock realtimeStock, int totalQuantity) {
        if (!realtimeStock.initialized()) {
            return;
        }

        int remainingStock = realtimeStock.remainingStock();
        if (remainingStock < 0 || remainingStock > totalQuantity) {
            throw new GeneralException(CouponErrorCode.REALTIME_STOCK_INCONSISTENT);
        }
    }
}
