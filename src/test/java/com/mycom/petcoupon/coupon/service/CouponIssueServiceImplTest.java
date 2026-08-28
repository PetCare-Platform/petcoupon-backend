package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
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

    private Coupon validCoupon;

    @BeforeEach
    void setUp() {
        validCoupon = Coupon.builder()
            .name("유효한 쿠폰")
            .discountType(DiscountType.FIXED_AMOUNT)
            .discountValue(1000)
            .minOrderAmount(5000)
            .issueStartAt(LocalDateTime.now().minusDays(1))
            .issueEndAt(LocalDateTime.now().plusDays(1))
            .validDays(7)
            .build();
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(validCoupon));
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
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(COUPON_ID, request, IDEMPOTENCY_KEY))
            .isInstanceOf(GeneralException.class)
            .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.COUPON_NOT_FOUND);

        verifyNoInteractions(couponIssueStreamProducer);
    }

    @Test
    void 오픈_전_쿠폰이면_발행_없이_예외를_던진다() {
        Coupon notStartedCoupon = Coupon.builder()
            .name("오픈 전 쿠폰")
            .discountType(DiscountType.FIXED_AMOUNT)
            .discountValue(1000)
            .minOrderAmount(5000)
            .issueStartAt(LocalDateTime.now().plusDays(1))
            .issueEndAt(LocalDateTime.now().plusDays(2))
            .validDays(7)
            .build();
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(notStartedCoupon));

        assertThatThrownBy(() -> service.issue(COUPON_ID, request, IDEMPOTENCY_KEY))
            .isInstanceOf(GeneralException.class)
            .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.COUPON_NOT_OPEN_YET);

        verifyNoInteractions(couponIssueStreamProducer);
    }

    @Test
    void 발급_종료된_쿠폰이면_발행_없이_예외를_던진다() {
        Coupon expiredCoupon = Coupon.builder()
            .name("종료된 쿠폰")
            .discountType(DiscountType.FIXED_AMOUNT)
            .discountValue(1000)
            .minOrderAmount(5000)
            .issueStartAt(LocalDateTime.now().minusDays(2))
            .issueEndAt(LocalDateTime.now().minusDays(1))
            .validDays(7)
            .build();
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(expiredCoupon));

        assertThatThrownBy(() -> service.issue(COUPON_ID, request, IDEMPOTENCY_KEY))
            .isInstanceOf(GeneralException.class)
            .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.COUPON_ISSUE_EXPIRED);

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
