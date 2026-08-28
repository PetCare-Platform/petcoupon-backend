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
import com.mycom.petcoupon.coupon.dto.res.CouponPipelineDrainStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.coupon.issue.service.CouponIssuePipelineDrainChecker;
import com.mycom.petcoupon.coupon.issue.service.PipelineDrainStatus;
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
    private CouponIssuePipelineDrainChecker pipelineDrainChecker;

    @Mock
    private CouponConverter couponConverter;

    @InjectMocks
    private CouponRealtimeStatusServiceImpl couponRealtimeStatusService;

    @Test
    void getRealtimeStatus_returnsResponse_whenCouponExists() {
        Coupon coupon = Coupon.builder().build();
        CouponStock couponStock = CouponStock.builder().coupon(coupon).totalQuantity(100).build();
        CouponIssueRealtimeStock realtimeStock = CouponIssueRealtimeStock.builder()
                .initialized(true)
                .remainingStock(60)
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

    @Test
    void getRealtimeStatus_throwsException_whenRemainingStockIsNegative() {
        Coupon coupon = Coupon.builder().build();
        CouponStock couponStock = CouponStock.builder().coupon(coupon).totalQuantity(100).build();
        CouponIssueRealtimeStock realtimeStock = CouponIssueRealtimeStock.builder()
                .initialized(true)
                .remainingStock(-1)
                .build();

        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
        when(couponStockRepository.findById(1L)).thenReturn(Optional.of(couponStock));
        when(couponIssueLuaService.getRealtimeStock(1L)).thenReturn(realtimeStock);

        assertThatThrownBy(() -> couponRealtimeStatusService.getRealtimeStatus(1L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.REALTIME_STOCK_INCONSISTENT);
    }

    @Test
    void getRealtimeStatus_throwsException_whenRemainingStockExceedsTotalQuantity() {
        Coupon coupon = Coupon.builder().build();
        CouponStock couponStock = CouponStock.builder().coupon(coupon).totalQuantity(100).build();
        CouponIssueRealtimeStock realtimeStock = CouponIssueRealtimeStock.builder()
                .initialized(true)
                .remainingStock(101)
                .build();

        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
        when(couponStockRepository.findById(1L)).thenReturn(Optional.of(couponStock));
        when(couponIssueLuaService.getRealtimeStock(1L)).thenReturn(realtimeStock);

        assertThatThrownBy(() -> couponRealtimeStatusService.getRealtimeStatus(1L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.REALTIME_STOCK_INCONSISTENT);
    }

    @Test
    void getRealtimeStatus_skipsValidation_whenNotInitialized() {
        Coupon coupon = Coupon.builder().build();
        CouponStock couponStock = CouponStock.builder().coupon(coupon).totalQuantity(100).build();
        CouponIssueRealtimeStock realtimeStock = CouponIssueRealtimeStock.builder()
                .initialized(false)
                .remainingStock(0)
                .build();
        CouponRealtimeStatusResponse expected = CouponRealtimeStatusResponse.builder()
                .couponId(1L)
                .totalQuantity(100)
                .remainingQuantity(100)
                .issuedQuantity(0)
                .initialized(false)
                .build();

        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
        when(couponStockRepository.findById(1L)).thenReturn(Optional.of(couponStock));
        when(couponIssueLuaService.getRealtimeStock(1L)).thenReturn(realtimeStock);
        when(couponConverter.toRealtimeStatusResponse(coupon, couponStock, realtimeStock)).thenReturn(expected);

        CouponRealtimeStatusResponse response = couponRealtimeStatusService.getRealtimeStatus(1L);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void getPipelineDrainStatus_returnsResponse_whenCouponExists() {
        Coupon coupon = Coupon.builder().build();
        PipelineDrainStatus drainStatus = new PipelineDrainStatus(0L, 0L, 0L, false);
        CouponPipelineDrainStatusResponse expected = CouponPipelineDrainStatusResponse.builder()
                .couponStatus(CouponStatus.ENDED)
                .outboxUnconsumed(0L)
                .streamUndelivered(0L)
                .streamActivePending(0L)
                .checkFailed(false)
                .build();

        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
        when(pipelineDrainChecker.check(1L)).thenReturn(drainStatus);
        when(couponConverter.toPipelineDrainStatusResponse(coupon, drainStatus)).thenReturn(expected);

        CouponPipelineDrainStatusResponse response = couponRealtimeStatusService.getPipelineDrainStatus(1L);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void getPipelineDrainStatus_throwsException_whenCouponNotFound() {
        when(couponRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponRealtimeStatusService.getPipelineDrainStatus(999L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }
}
