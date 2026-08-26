package com.mycom.petcoupon.coupon.issue.dto.enums;

public enum CouponIssueStockRestoreStatus {

	RESTORED, 
	ALREADY_RESTORED, 
	REQUEST_MISMATCH, 
	STOCK_NOT_INITIALIZED, 
	INCONSISTENT_STATE;

	public static CouponIssueStockRestoreStatus from(long code) {
		return switch ((int) code) {
		case 1 -> RESTORED;
		case 2 -> ALREADY_RESTORED;
		case 3 -> REQUEST_MISMATCH;
		case 4 -> STOCK_NOT_INITIALIZED;
		case 5 -> INCONSISTENT_STATE;
		default -> throw new IllegalArgumentException("알 수 없는 Redis 재고 복구 결과 코드입니다. code=" + code);
		};
	}
}
