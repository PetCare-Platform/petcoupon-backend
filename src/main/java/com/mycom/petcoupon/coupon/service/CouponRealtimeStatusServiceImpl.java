package com.mycom.petcoupon.coupon.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponRealtimeStatusServiceImpl implements CouponRealtimeStatusService {

    private final CouponRepository couponRepository;
    private final CouponStockRepository couponStockRepository;
    private final CouponIssueLuaService couponIssueLuaService;
    private final CouponConverter couponConverter;

    @Override
    @Transactional(readOnly = true)
    public CouponRealtimeStatusResponse getRealtimeStatus(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        // total_quantity는 쿠폰 생성 시점에 고정되고 CouponStock은 항상 Coupon과 같이 생성되므로
        // (CouponConverter.toCouponStock 참고), 여기서 없다면 데이터 정합성이 깨진 상태다.
        CouponStock couponStock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        // 잔여 재고·발급 완료 수는 DB(CouponStock)가 아니라 Redis(Lua가 관리하는 실시간 값)를 그대로 쓴다 —
        // DB 쪽은 Kafka 소비 이후에야 갱신되는 최종 정합값이라 "실시간"과는 갱신 시점이 다르다.
        CouponIssueRealtimeStock realtimeStock = couponIssueLuaService.getRealtimeStock(couponId);

        validateRealtimeStock(realtimeStock, couponStock.getTotalQuantity());

        return couponConverter.toRealtimeStatusResponse(coupon, couponStock, realtimeStock);
    }

    // Redis 값이 정상적인 Lua 실행 경로로만 바뀐다면 항상 0 <= remainingStock <= totalQuantity지만,
    // 수동 조작이나 초기화 버그로 이 범위를 벗어날 수 있다 — 미초기화(0)는 정상 상태라 검증 대상이 아니다.
    private void validateRealtimeStock(CouponIssueRealtimeStock realtimeStock, int totalQuantity) {
        if (!realtimeStock.initialized()) {
            return;
        }

        int remainingStock = realtimeStock.remainingStock();
        if (remainingStock < 0 || remainingStock > totalQuantity) {
            throw new GeneralException(CouponErrorCode.REALTIME_STOCK_INCONSISTENT);
        }
    }
}
