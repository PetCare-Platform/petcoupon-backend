package com.mycom.petcoupon.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.coupon.entity.CouponIssueHistory;

public interface CouponIssueHistoryRepository extends JpaRepository<CouponIssueHistory, Long> {
}
