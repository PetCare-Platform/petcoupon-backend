package com.mycom.petcoupon.coupon.issue.dto;

import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;

import lombok.Builder;

@Builder
public record CouponIssueLuaResult(
	CouponIssueLuaResultStatus status,
	Long sequenceNo
) {}
