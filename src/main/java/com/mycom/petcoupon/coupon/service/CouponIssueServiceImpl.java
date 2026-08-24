package com.mycom.petcoupon.coupon.service;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueStreamProducer;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/**
 * 선착순 쿠폰 신청 오케스트레이션.
 * 확정된 파이프라인(API 요청 -> Redis Stream 대기열 -> Redis Lua -> Outbox -> Kafka -> 비동기 DB 저장)에서
 * 이 클래스가 담당하는 건 검증 + Stream 발행까지다. 재고 판정(Lua)은 API 요청 시점이 아니라
 * Stream을 소비하는 쪽(#57/#60)에서 비동기로 일어나므로, 여기서는 SUCCESS/SOLD_OUT을 알 수 없다 —
 * 그래서 응답은 항상 "WAITING"(접수됨)이다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssueServiceImpl implements CouponIssueService {

    private final CouponRepository couponRepository;
    private final CouponIssueStreamProducer couponIssueStreamProducer;
    private final CouponIssueConverter couponIssueConverter;

    @Override
    public CouponIssueCreateResponse issue(Long couponId, CouponIssueCreateRequest request, String requestId) {
        // 존재하지 않는 쿠폰이면 Stream에 넣을 필요 없이 여기서 바로 차단
        if (!couponRepository.existsById(couponId)) {
            throw new GeneralException(CouponErrorCode.COUPON_NOT_FOUND);
        }

        couponIssueStreamProducer.publish(couponId, request.userId(), requestId);

        return couponIssueConverter.toCreateResponse(couponId, request.userId());
    }
}
