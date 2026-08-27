package com.mycom.petcoupon.coupon.service;

import java.util.List;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueDetailResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueRequestResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyStatusResult;

public interface CouponIssueQueryService {

    CouponIssueStatusResponse getStatus(Long couponIssueId);

    CouponIssueDetailResponse getDetail(Long couponIssueId);

    List<CouponIssueRequestResponse> getIssueRequests(Long userId, IssueStatus status);

    // Idempotency-Key로 신청한 발급 요청의 처리 상태를 조회한다 — 비동기 파이프라인에서 클라이언트가 폴링하는 용도.
    IdempotencyKeyStatusResult getRequestStatus(Long userId, String idempotencyKey);
}