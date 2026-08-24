package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueStreamProducer;
import com.mycom.petcoupon.coupon.redis.CouponIssueResult;
import com.mycom.petcoupon.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

class CouponIssueServiceImplTest {

    private static final Long COUPON_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String IDEMPOTENCY_KEY = "idem-key-1";

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final RedisCouponStockService redisCouponStockService = mock(RedisCouponStockService.class);
    private final CouponIssueStreamProducer couponIssueStreamProducer = mock(CouponIssueStreamProducer.class);
    private final CouponIssueConverter couponIssueConverter = new CouponIssueConverter();

    private final CouponIssueServiceImpl service = new CouponIssueServiceImpl(
        couponRepository,
        redisCouponStockService,
        couponIssueStreamProducer,
        couponIssueConverter
    );

    private final CouponIssueCreateRequest request = CouponIssueCreateRequest.builder()
        .userId(USER_ID)
        .build();

    @BeforeEach
    void setUp() {
        when(couponRepository.existsById(COUPON_ID)).thenReturn(true);
    }

    @Test
    void 재고_차감과_발행이_모두_성공하면_정상_응답을_반환한다() {
        when(redisCouponStockService.decreaseStock(eq(COUPON_ID), eq(USER_ID), anyString()))
            .thenReturn(CouponIssueResult.SUCCESS);

        CouponIssueCreateResponse response = service.issue(COUPON_ID, request, IDEMPOTENCY_KEY);

        assertThat(response.couponId()).isEqualTo(COUPON_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
    }

    @Test
    void Idempotency_Key를_Redis_requestId로_그대로_사용한다() {
        when(redisCouponStockService.decreaseStock(COUPON_ID, USER_ID, IDEMPOTENCY_KEY))
            .thenReturn(CouponIssueResult.SUCCESS);

        service.issue(COUPON_ID, request, IDEMPOTENCY_KEY);

        verify(redisCouponStockService).decreaseStock(COUPON_ID, USER_ID, IDEMPOTENCY_KEY);
        verify(couponIssueStreamProducer).publish(COUPON_ID, USER_ID, IDEMPOTENCY_KEY);
    }

    @Test
    void 재고_차감이_실패하면_발행을_호출하지_않는다() {
        when(redisCouponStockService.decreaseStock(eq(COUPON_ID), eq(USER_ID), anyString()))
            .thenReturn(CouponIssueResult.SOLD_OUT);

        assertThatThrownBy(() -> service.issue(COUPON_ID, request, IDEMPOTENCY_KEY))
            .isInstanceOf(GeneralException.class);

        verify(couponIssueStreamProducer, never()).publish(anyLong(), anyLong(), anyString());
    }

    @Test
    void 발행이_실패하면_차감된_재고를_복구하고_예외를_전파한다() {
        when(redisCouponStockService.decreaseStock(eq(COUPON_ID), eq(USER_ID), anyString()))
            .thenReturn(CouponIssueResult.SUCCESS);
        when(couponIssueStreamProducer.publish(any(), any(), any()))
            .thenThrow(new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED));

        assertThatThrownBy(() -> service.issue(COUPON_ID, request, IDEMPOTENCY_KEY))
            .isInstanceOf(GeneralException.class);

        verify(redisCouponStockService).restoreStock(COUPON_ID, USER_ID, IDEMPOTENCY_KEY);
    }

    @Test
    void 롤백_자체가_실패해도_원래_발행_실패_예외를_그대로_전파한다() {
        when(redisCouponStockService.decreaseStock(eq(COUPON_ID), eq(USER_ID), anyString()))
            .thenReturn(CouponIssueResult.SUCCESS);
        GeneralException publishFailure = new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
        when(couponIssueStreamProducer.publish(any(), any(), any())).thenThrow(publishFailure);
        org.mockito.Mockito.doThrow(new UnsupportedOperationException("롤백 스크립트 미구현"))
            .when(redisCouponStockService).restoreStock(any(), any(), any());

        assertThatThrownBy(() -> service.issue(COUPON_ID, request, IDEMPOTENCY_KEY))
            .isSameAs(publishFailure);
    }
}
