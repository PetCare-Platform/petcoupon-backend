package com.mycom.petcoupon.coupon.issue.dto;

import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueStockRestoreStatus;

import lombok.Builder;

@Builder 
public record CouponIssueStockRestoreResult(
	CouponIssueStockRestoreStatus status,
	int remainingStock
) {}
