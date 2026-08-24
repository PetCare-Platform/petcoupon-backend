package com.mycom.petcoupon.coupon.service;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueStreamProducer;
import com.mycom.petcoupon.coupon.redis.CouponIssueResult;
import com.mycom.petcoupon.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.global.common.code.BaseErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/**
 * 선착순 쿠폰 신청 오케스트레이션.
 * 담당3의 Redis 재고 차감(지금은 mock) 결과를 해석해서
 * 성공이면 응답 DTO로, 실패면 GeneralException으로 변환해서 던진다.
 * Redis 차감 성공 후에는 Stream 발행까지 이어가고, 발행이 실패하면 차감된 재고를 되돌린다(#58).
 */
@Service
@RequiredArgsConstructor
public class CouponIssueServiceImpl implements CouponIssueService {

    private final CouponRepository couponRepository;
    private final RedisCouponStockService redisCouponStockService;
    private final CouponIssueStreamProducer couponIssueStreamProducer;
    private final CouponIssueConverter couponIssueConverter;

    @Override
    public CouponIssueCreateResponse issue(Long couponId, CouponIssueCreateRequest request, String idempotencyKey) {
        // 존재하지 않는 쿠폰이면 Redis 호출까지 갈 필요 없이 여기서 바로 차단
        if (!couponRepository.existsById(couponId)) {
            throw new GeneralException(CouponErrorCode.COUPON_NOT_FOUND);
        }

        // 클라이언트가 보낸 Idempotency-Key를 그대로 Redis 레벨 requestId로 쓴다.
        // API 레벨 재전송은 CouponController의 IdempotencyKeyService가 이미 막아주지만,
        // 죽은 IN_PROGRESS 시도를 reclaim해서 이 메서드가 같은 요청으로 다시 호출되는 경우엔
        // 이 값이 같아야 Redis 쪽 dedup(SAME_REQUEST_RETRY)도 같은 요청으로 인식한다.
        String requestId = idempotencyKey;

        CouponIssueResult result = redisCouponStockService.decreaseStock(couponId, request.userId(), requestId);

        // SUCCESS가 아니면 바로 예외로 던진다 — 실패 응답 포맷은 GlobalExceptionHandler가 만들어줌
        if (result != CouponIssueResult.SUCCESS) {
            throw new GeneralException(toErrorCode(result));
        }

        try {
            couponIssueStreamProducer.publish(couponId, request.userId(), requestId);
        } catch (RuntimeException e) {
            // 발행 실패 시 이미 차감된 Redis 재고를 되돌려서 고아 차감이 남지 않게 한다
            redisCouponStockService.restoreStock(couponId, request.userId(), requestId);
            throw e;
        }

        return couponIssueConverter.toCreateResponse(couponId, request.userId());
    }

    // Redis 판정 결과 -> 쿠폰 도메인 에러코드 매핑
    private BaseErrorCode toErrorCode(CouponIssueResult result) {
        return switch (result) {
            case SOLD_OUT -> CouponErrorCode.SOLD_OUT;
            case DUPLICATE_USER -> CouponErrorCode.DUPLICATE_USER;
            case DUPLICATE_REQUEST -> CouponErrorCode.DUPLICATE_REQUEST;
            case SUCCESS -> throw new IllegalStateException("SUCCESS 는 여기 안 옴"); // 방어 코드, 위에서 이미 걸러짐
        };
    }
}
