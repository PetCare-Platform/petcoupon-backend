package com.mycom.petcoupon.coupon.repository;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;

/**
 * 목록 조회에서 쿠폰과 재고를 한 번에 실어 나르는 조회 전용 묶음.
 *
 * Coupon에는 CouponStock으로 가는 연관관계가 없다(주인은 CouponStock 쪽 @MapsId).
 * 그렇다고 Coupon에 역방향 @OneToOne을 달면 OneToOne의 mappedBy 측은 프록시가 되지 않아
 * 쿠폰을 읽는 모든 경로(발급 포함)에 재고 SELECT가 한 번씩 따라붙는다.
 * 그래서 연관관계를 늘리는 대신 목록 쿼리에서만 엔티티 조인으로 묶어 이 타입으로 받는다.
 */
public record CouponWithStock(Coupon coupon, CouponStock couponStock) {
}
