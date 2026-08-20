package com.mycom.petcoupon.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.coupon.entity.CouponIssue;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {
}