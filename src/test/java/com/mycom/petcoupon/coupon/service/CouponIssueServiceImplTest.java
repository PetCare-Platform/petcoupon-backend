package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueStreamProducer;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

class CouponIssueServiceImplTest {

    private static final Long COUPON_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String IDEMPOTENCY_KEY = "idem-key-1";

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final CouponIssueStreamProducer couponIssueStreamProducer = mock(CouponIssueStreamProducer.class);
    private final CouponIssueConverter couponIssueConverter = new CouponIssueConverter();

    private final CouponIssueServiceImpl service = new CouponIssueServiceImpl(
        couponRepository,
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
    void 검증을_통과하면_Stream에_발행하고_WAITING_응답을_반환한다() {
        CouponIssueCreateResponse response = service.issue(COUPON_ID, request, IDEMPOTENCY_KEY);

        assertThat(response.couponId()).isEqualTo(COUPON_ID);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.status()).isEqualTo("WAITING");
    }

    @Test
    void Idempotency_Key를_Stream_requestId로_그대로_사용한다() {
        service.issue(COUPON_ID, request, IDEMPOTENCY_KEY);

        verify(couponIssueStreamProducer).publish(COUPON_ID, USER_ID, IDEMPOTENCY_KEY);
    }

    @Test
    void 존재하지_않는_쿠폰이면_발행_없이_예외를_던진다() {
        when(couponRepository.existsById(COUPON_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(COUPON_ID, request, IDEMPOTENCY_KEY))
            .isInstanceOf(GeneralException.class);

        verifyNoInteractions(couponIssueStreamProducer);
    }

    @Test
    void 발행이_실패하면_예외를_그대로_전파한다() {
        GeneralException publishFailure = new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
        when(couponIssueStreamProducer.publish(any(), any(), any())).thenThrow(publishFailure);

        assertThatThrownBy(() -> service.issue(COUPON_ID, request, IDEMPOTENCY_KEY))
            .isSameAs(publishFailure);
    }
}
