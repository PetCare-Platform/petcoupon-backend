package com.mycom.petcoupon.reconciliation.dto.res;

import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;

import lombok.Builder;

@Builder
public record VerificationDetailResponse(
        VerificationErrorType errorType,
        Long couponIssueId,
        Long userId,
        String expectedValue,
        String actualValue,
        String message
) {
}
