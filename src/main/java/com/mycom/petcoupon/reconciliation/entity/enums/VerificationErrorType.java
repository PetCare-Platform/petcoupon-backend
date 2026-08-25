package com.mycom.petcoupon.reconciliation.entity.enums;

public enum VerificationErrorType {
	STOCK_MISMATCH, 
	DUPLICATE_ISSUE, 
	INVALID_STATUS, 
	HISTORY_MISMATCH, 
	DUPLICATE_CONSUME,
	SEQUENCE_GAP,
	STOCK_NOT_RESTORED
}
