package com.mycom.petcoupon.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MockRedisCouponStockServiceTest {

    private static final Long COUPON_ID = 1L;
    private static final int DEFAULT_STOCK = 5;

    private final MockRedisCouponStockService service = new MockRedisCouponStockService();

    @Test
    void 복구하면_차감됐던_재고만큼_다시_신청을_받을_수_있다() {
        // 기본 재고(5) 전부 소진
        for (long userId = 1; userId <= DEFAULT_STOCK; userId++) {
            CouponIssueResult result = service.decreaseStock(COUPON_ID, userId, "request-" + userId);
            assertThat(result).isEqualTo(CouponIssueResult.SUCCESS);
        }
        assertThat(service.decreaseStock(COUPON_ID, 999L, "request-999")).isEqualTo(CouponIssueResult.SOLD_OUT);

        // 그 중 하나(userId=1)를 복구
        service.restoreStock(COUPON_ID, 1L, "request-1");

        // 복구된 만큼 새 유저가 다시 성공할 수 있어야 한다
        assertThat(service.decreaseStock(COUPON_ID, 1000L, "request-1000")).isEqualTo(CouponIssueResult.SUCCESS);
    }

    @Test
    void 복구된_유저는_새_요청으로_재신청할_수_있다() {
        service.decreaseStock(COUPON_ID, 1L, "request-1");

        service.restoreStock(COUPON_ID, 1L, "request-1");

        CouponIssueResult result = service.decreaseStock(COUPON_ID, 1L, "request-2");

        assertThat(result).isEqualTo(CouponIssueResult.SUCCESS);
    }
}
