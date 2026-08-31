package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.converter.IssueStatisticsConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueTimeSeriesResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponPipelineDrainStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.IssueThroughputBucketResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.coupon.issue.service.CouponIssuePipelineDrainChecker;
import com.mycom.petcoupon.coupon.issue.service.PipelineDrainStatus;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponRealtimeStatusServiceImpl implements CouponRealtimeStatusService {

    private static final DateTimeFormatter TIME_SERIES_BUCKET_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CouponRepository couponRepository;
    private final CouponStockRepository couponStockRepository;
    private final CouponIssueLuaService couponIssueLuaService;
    private final CouponRealtimeStockValidator realtimeStockValidator;
    private final CouponIssuePipelineDrainChecker pipelineDrainChecker;
    private final CouponConverter couponConverter;
    private final IssueMessageRepository issueMessageRepository;
    private final IssueStatisticsConverter issueStatisticsConverter;

    @Override
    @Transactional(readOnly = true)
    public CouponRealtimeStatusResponse getRealtimeStatus(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        // total_quantity는 쿠폰 생성 시점에 고정되고 CouponStock은 항상 Coupon과 같이 생성되므로
        // (CouponConverter.toCouponStock 참고), 여기서 없다면 데이터 정합성이 깨진 상태다.
        CouponStock couponStock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        // 잔여 재고·발급 완료 수는 DB(CouponStock)가 아니라 Redis(Lua가 관리하는 실시간 값)를 그대로 쓴다 —
        // DB 쪽은 Kafka 소비 이후에야 갱신되는 최종 정합값이라 "실시간"과는 갱신 시점이 다르다.
        CouponIssueRealtimeStock realtimeStock = couponIssueLuaService.getRealtimeStock(couponId);

        realtimeStockValidator.validate(realtimeStock, couponStock.getTotalQuantity());

        return couponConverter.toRealtimeStatusResponse(coupon, couponStock, realtimeStock);
    }

    @Override
    @Transactional(readOnly = true)
    public CouponPipelineDrainStatusResponse getPipelineDrainStatus(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        PipelineDrainStatus drainStatus = pipelineDrainChecker.check(couponId);

        return couponConverter.toPipelineDrainStatusResponse(coupon, drainStatus);
    }

    @Override
    @Transactional(readOnly = true)
    public CouponIssueTimeSeriesResponse getIssueTimeSeries(Long couponId, int windowSeconds, int bucketSeconds) {
        // windowSeconds는 bucketSeconds의 배수여야 한다 — 나누어떨어지지 않으면 실제 조회 구간과 요청 구간이 달라진다.
        if (windowSeconds <= 0 || bucketSeconds <= 0 || bucketSeconds > windowSeconds || windowSeconds % bucketSeconds != 0) {
            throw new GeneralException(CommonErrorCode.NOT_VALID_ERROR);
        }

        if (!couponRepository.existsById(couponId)) {
            throw new GeneralException(CouponErrorCode.COUPON_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        // created_at < to 경계이므로 현재 초까지 포함하기 위해 to를 now.plusSeconds(1)로 설정한다.
        LocalDateTime to = now.plusSeconds(1);
        LocalDateTime from = to.minusSeconds(windowSeconds);

        List<IssueThroughputBucketResponse> timeSeries = buildTimeSeries(couponId, bucketSeconds, from, to);

        return CouponIssueTimeSeriesResponse.builder()
                .couponId(couponId)
                .windowSeconds(windowSeconds)
                .bucketSeconds(bucketSeconds)
                .timeSeries(timeSeries)
                .build();
    }

    private List<IssueThroughputBucketResponse> buildTimeSeries(
            Long couponId, int bucketSeconds, LocalDateTime from, LocalDateTime to
    ) {
        Map<String, IssueThroughputBucketResponse> bucketsByKey = issueMessageRepository
                .findThroughputByCouponAndSeconds(couponId, bucketSeconds, from, to)
                .stream()
                .map(issueStatisticsConverter::toBucketResponse)
                .collect(Collectors.toMap(IssueThroughputBucketResponse::bucket, Function.identity(), (a, b) -> a));

        List<IssueThroughputBucketResponse> result = new ArrayList<>();
        LocalDateTime current = from;
        while (current.isBefore(to)) {
            String bucketKey = current.format(TIME_SERIES_BUCKET_FORMATTER);
            IssueThroughputBucketResponse existing = bucketsByKey.get(bucketKey);
            result.add(existing != null ? existing : emptyBucket(bucketKey));
            current = current.plusSeconds(bucketSeconds);
        }
        return result;
    }

    private IssueThroughputBucketResponse emptyBucket(String bucketKey) {
        return IssueThroughputBucketResponse.builder()
                .bucket(bucketKey)
                .issuedCount(0L)
                .failedCount(0L)
                .inProgressCount(0L)
                .build();
    }

}
