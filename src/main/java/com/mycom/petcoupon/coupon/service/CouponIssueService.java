package com.mycom.petcoupon.coupon.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.redis.CouponIssueResult;
import com.mycom.petcoupon.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.global.common.code.BaseErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/**
 * 선착순 쿠폰 신청 오케스트레이션.
 * 담당3의 Redis 재고 차감(지금은 mock) 결과를 해석해서
 * 성공이면 응답 DTO로, 실패면 GeneralException으로 변환해서 던진다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final RedisCouponStockService redisCouponStockService;

    private final CouponIssueConverter couponConverter;

    public CouponIssueCreateResponse issue(Long couponId, CouponIssueCreateRequest request) {
        // TODO(2단계): 클라이언트가 보낸 Idempotency-Key로 대체 예정.
        // 지금처럼 매번 새 UUID를 쓰면 같은 요청을 재전송해도 DUPLICATE_REQUEST가 절대 안 잡힘.
        String requestId = UUID.randomUUID().toString();

        CouponIssueResult result = redisCouponStockService.decreaseStock(couponId, request.userId(), requestId);

        // SUCCESS가 아니면 바로 예외로 던진다 — 실패 응답 포맷은 GlobalExceptionHandler가 만들어줌
        if(result != CouponIssueResult.SUCCESS) {
            throw new GeneralException(toErrorCode(result));
        }
        return couponConverter.toCreateResponse(couponId, request.userId());
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
