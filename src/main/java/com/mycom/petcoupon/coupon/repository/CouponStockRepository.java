package com.mycom.petcoupon.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.coupon.entity.CouponStock;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {
}
