package com.mycom.petcoupon.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.global.common.exception.GeneralException;

class LuaRedisCouponStockServiceTest {

    private static final Long COUPON_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String REQUEST_ID = "request-1";

    private final CouponIssueLuaService couponIssueLuaService = mock(CouponIssueLuaService.class);
    private final LuaRedisCouponStockService service = new LuaRedisCouponStockService(couponIssueLuaService);

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, SUCCESS",
        "SOLD_OUT, SOLD_OUT",
        "ALREADY_APPLIED, DUPLICATE_USER",
        "SAME_REQUEST_RETRY, DUPLICATE_REQUEST"
    })
    void Lua_결과를_CouponIssueResult로_변환한다(CouponIssueLuaResultStatus luaResult, CouponIssueResult expected) {
        when(couponIssueLuaService.issue(COUPON_ID, USER_ID, REQUEST_ID)).thenReturn(luaResult);

        CouponIssueResult result = service.decreaseStock(COUPON_ID, USER_ID, REQUEST_ID);

        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"STOCK_NOT_INITIALIZED"})
    void 재고_키가_초기화되지_않았으면_예외를_던진다(CouponIssueLuaResultStatus luaResult) {
        when(couponIssueLuaService.issue(COUPON_ID, USER_ID, REQUEST_ID)).thenReturn(luaResult);

        assertThatThrownBy(() -> service.decreaseStock(COUPON_ID, USER_ID, REQUEST_ID))
            .isInstanceOf(GeneralException.class)
            .extracting(ex -> ((GeneralException) ex).getErrorCode())
            .isEqualTo(CouponErrorCode.STOCK_NOT_INITIALIZED);
    }

    @org.junit.jupiter.api.Test
    void 재고_복구는_아직_구현되지_않아서_예외를_던진다() {
        assertThatThrownBy(() -> service.restoreStock(COUPON_ID, USER_ID, REQUEST_ID))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
