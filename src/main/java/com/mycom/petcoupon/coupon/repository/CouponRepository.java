package com.mycom.petcoupon.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.coupon.entity.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
