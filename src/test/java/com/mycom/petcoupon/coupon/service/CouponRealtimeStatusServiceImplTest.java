package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.converter.IssueStatisticsConverter;
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
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

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

    @Mock
    private IssueMessageRepository issueMessageRepository;

    @Mock
    private IssueStatisticsConverter issueStatisticsConverter;

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

    @Test
    void getIssueTimeSeries_returnsTimeSeriesWithZeroFilling_whenCouponExists() {
        Long couponId = 1L;
        int windowSeconds = 90;
        int bucketSeconds = 5;

        when(couponRepository.existsById(couponId)).thenReturn(true);

        com.mycom.petcoupon.messaging.repository.IssueThroughputBucket mockBucket =
                org.mockito.Mockito.mock(com.mycom.petcoupon.messaging.repository.IssueThroughputBucket.class);

        java.util.concurrent.atomic.AtomicReference<java.time.LocalDateTime> capturedFrom = new java.util.concurrent.atomic.AtomicReference<>();
        when(issueMessageRepository.findThroughputByCouponAndSeconds(
                org.mockito.ArgumentMatchers.eq(couponId),
                org.mockito.ArgumentMatchers.eq(bucketSeconds),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> {
            capturedFrom.set(invocation.getArgument(2));
            return List.of(mockBucket);
        });

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        when(issueStatisticsConverter.toBucketResponse(mockBucket)).thenAnswer(invocation ->
                com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse.builder()
                        .bucket(capturedFrom.get().format(formatter))
                        .issuedCount(10L)
                        .failedCount(1L)
                        .inProgressCount(2L)
                        .build()
        );

        com.mycom.petcoupon.coupon.dto.res.CouponIssueTimeSeriesResponse response =
                couponRealtimeStatusService.getIssueTimeSeries(couponId, windowSeconds, bucketSeconds);

        assertThat(response.couponId()).isEqualTo(couponId);
        assertThat(response.windowSeconds()).isEqualTo(windowSeconds);
        assertThat(response.bucketSeconds()).isEqualTo(bucketSeconds);
        assertThat(response.timeSeries()).isNotEmpty();

        var firstBucket = response.timeSeries().get(0);
        assertThat(firstBucket.bucket()).isEqualTo(capturedFrom.get().format(formatter));
        assertThat(firstBucket.issuedCount()).isEqualTo(10L);
        assertThat(firstBucket.failedCount()).isEqualTo(1L);
        assertThat(firstBucket.inProgressCount()).isEqualTo(2L);

        // 첫 번째 버킷을 제외한 나머지 버킷들은 0건으로 zero-filling 되어야 함
        assertThat(response.timeSeries().subList(1, response.timeSeries().size())).allSatisfy(bucket -> {
            assertThat(bucket.issuedCount()).isZero();
            assertThat(bucket.failedCount()).isZero();
            assertThat(bucket.inProgressCount()).isZero();
        });
    }

    @Test
    void getIssueTimeSeries_throwsException_whenCouponNotFound() {
        when(couponRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> couponRealtimeStatusService.getIssueTimeSeries(999L, 90, 5))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void getIssueTimeSeries_throwsException_whenWindowSecondsIsZeroOrNegative() {
        assertThatThrownBy(() -> couponRealtimeStatusService.getIssueTimeSeries(1L, 0, 5))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(com.mycom.petcoupon.global.common.code.CommonErrorCode.NOT_VALID_ERROR);

        assertThatThrownBy(() -> couponRealtimeStatusService.getIssueTimeSeries(1L, -10, 5))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(com.mycom.petcoupon.global.common.code.CommonErrorCode.NOT_VALID_ERROR);
    }

    @Test
    void getIssueTimeSeries_throwsException_whenBucketSecondsIsZeroOrNegative() {
        assertThatThrownBy(() -> couponRealtimeStatusService.getIssueTimeSeries(1L, 90, 0))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(com.mycom.petcoupon.global.common.code.CommonErrorCode.NOT_VALID_ERROR);

        assertThatThrownBy(() -> couponRealtimeStatusService.getIssueTimeSeries(1L, 90, -5))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(com.mycom.petcoupon.global.common.code.CommonErrorCode.NOT_VALID_ERROR);
    }

    @Test
    void getIssueTimeSeries_throwsException_whenBucketSecondsExceedsWindowSeconds() {
        assertThatThrownBy(() -> couponRealtimeStatusService.getIssueTimeSeries(1L, 30, 60))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(com.mycom.petcoupon.global.common.code.CommonErrorCode.NOT_VALID_ERROR);
    }
}
