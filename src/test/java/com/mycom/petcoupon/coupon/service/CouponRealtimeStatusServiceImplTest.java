package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@ExtendWith(MockitoExtension.class)
class CouponRealtimeStatusServiceImplTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private CouponIssueLuaService couponIssueLuaService;

    @Mock
    private CouponConverter couponConverter;

    @InjectMocks
    private CouponRealtimeStatusServiceImpl couponRealtimeStatusService;

    @Test
    void getRealtimeStatus_returnsResponse_whenCouponExists() {
        Coupon coupon = Coupon.builder().build();
        CouponStock couponStock = CouponStock.builder().coupon(coupon).totalQuantity(100).build();
        CouponIssueRealtimeStock realtimeStock = CouponIssueRealtimeStock.builder()
                .remainingStock(60)
                .issuedCount(40)
                .build();
        CouponRealtimeStatusResponse expected = CouponRealtimeStatusResponse.builder()
                .couponId(1L)
                .totalQuantity(100)
                .remainingQuantity(60)
                .issuedQuantity(40)
                .build();

        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
        when(couponStockRepository.findById(1L)).thenReturn(Optional.of(couponStock));
        when(couponIssueLuaService.getRealtimeStock(1L)).thenReturn(realtimeStock);
        when(couponConverter.toRealtimeStatusResponse(coupon, couponStock, realtimeStock)).thenReturn(expected);

        CouponRealtimeStatusResponse response = couponRealtimeStatusService.getRealtimeStatus(1L);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void getRealtimeStatus_throwsException_whenCouponNotFound() {
        when(couponRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponRealtimeStatusService.getRealtimeStatus(999L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }
}
