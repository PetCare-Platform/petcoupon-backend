package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.global.common.exception.GeneralException;

class CouponRealtimeStockValidatorTest {

    private final CouponRealtimeStockValidator validator = new CouponRealtimeStockValidator();

    @Test
    void validate_skipsValidation_whenStockIsNotInitialized() {
        CouponIssueRealtimeStock stock = stock(false, -1);

        assertThatCode(() -> validator.validate(stock, 100)).doesNotThrowAnyException();
    }

    @Test
    void validate_acceptsBoundaryValues() {
        assertThatCode(() -> validator.validate(stock(true, 0), 100)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(stock(true, 100), 100)).doesNotThrowAnyException();
    }

    @Test
    void validate_throwsException_whenRemainingStockIsNegative() {
        assertInconsistentStock(stock(true, -1), 100);
    }

    @Test
    void validate_throwsException_whenRemainingStockExceedsTotalQuantity() {
        assertInconsistentStock(stock(true, 101), 100);
    }

    private void assertInconsistentStock(CouponIssueRealtimeStock stock, int totalQuantity) {
        assertThatThrownBy(() -> validator.validate(stock, totalQuantity))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.REALTIME_STOCK_INCONSISTENT);
    }

    private CouponIssueRealtimeStock stock(boolean initialized, int remainingStock) {
        return CouponIssueRealtimeStock.builder()
                .initialized(initialized)
                .remainingStock(remainingStock)
                .build();
    }
}
