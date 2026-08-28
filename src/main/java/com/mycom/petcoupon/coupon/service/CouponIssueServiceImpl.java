package com.mycom.petcoupon.coupon.service;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.issue.producer.CouponIssueStreamProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 선착순 쿠폰 신청 오케스트레이션.
 * 확정된 파이프라인(API 요청 -> Redis Stream 대기열 -> Redis Lua -> Outbox -> Kafka -> 비동기 DB 저장)에서
 * 이 클래스가 담당하는 건 Stream 발행까지다.
 * 쿠폰 존재 및 발급 가능 기간(issueStartAt ~ issueEndAt) 검증은 컨트롤러(CouponController)에서
 * 멱등키 등록 전 Fail-Fast로 수행하므로 여기서는 중복 조회 없이 바로 Stream으로 발행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueServiceImpl implements CouponIssueService {

    private final CouponIssueStreamProducer couponIssueStreamProducer;
    private final CouponIssueConverter couponIssueConverter;

    @Override
    public CouponIssueCreateResponse issue(Long couponId, CouponIssueCreateRequest request, String requestId) {
        // 선착순 순서 검증용: 도착 순서를 알 수 있는 유일한 지점이라 다른 검증보다 먼저 남긴다.
        log.info("[ISSUE] 접수 requestId={} couponId={} userId={}", requestId, couponId, request.userId());

        couponIssueStreamProducer.publish(couponId, request.userId(), requestId);

        return couponIssueConverter.toCreateResponse(couponId, request.userId());
    }
}
