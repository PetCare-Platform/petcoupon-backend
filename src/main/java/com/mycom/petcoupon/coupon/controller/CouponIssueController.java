package com.mycom.petcoupon.coupon.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.req.CouponIssueCancelRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponIssueUseRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDetailResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueRequestResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueRequestStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.service.CouponIssueCancelService;
import com.mycom.petcoupon.coupon.service.CouponIssueQueryService;
import com.mycom.petcoupon.coupon.service.CouponIssueUseService;
import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyStatusResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequiredArgsConstructor
public class CouponIssueController {

    private final CouponIssueQueryService couponIssueQueryService;
    private final CouponIssueUseService couponIssueUseService;
    private final CouponIssueCancelService couponIssueCancelService;

    // 사용자 본인의 쿠폰 발급 신청 내역 전체를 최신순으로 조회 (건당 상세/상태 조회와 달리 목록 API)
    // status 미지정 시 전체 조회, 지정 시 해당 상태만 필터링
    @GetMapping("/users/{userId}/coupon-issue-requests")
    public CustomResponse<List<CouponIssueRequestResponse>> getCouponIssueRequests(
            @PathVariable("userId") @Positive Long userId,
            @RequestParam(value = "status", required = false) IssueStatus status) {
        List<CouponIssueRequestResponse> response = couponIssueQueryService.getIssueRequests(userId, status);
        return CustomResponse.onSuccess(response);
    }

    @GetMapping("/coupon-issues/{couponIssueId}")
    public CustomResponse<CouponIssueDetailResponse> getCouponIssueDetail(
            @PathVariable("couponIssueId") @Positive Long couponIssueId) {
        CouponIssueDetailResponse response = couponIssueQueryService.getDetail(couponIssueId);
        return CustomResponse.onSuccess(response);
    }

    // 비동기 발급 파이프라인에서 클라이언트가 폴링하는 용도 — 신청 시 보낸 Idempotency-Key로
    // (재실행 없이) 그때 응답을 그대로 재현한다. 아직 안 끝났으면 IN_PROGRESS, 키 자체가 없으면 404.
    @GetMapping("/users/{userId}/coupon-issue-requests/status")
    public ResponseEntity<?> getCouponIssueRequestStatus(
            @PathVariable("userId") @Positive Long userId,
            @RequestParam("idempotencyKey") @NotBlank String idempotencyKey) {

        IdempotencyKeyStatusResult result = couponIssueQueryService.getRequestStatus(userId, idempotencyKey);

        return switch (result.type()) {
            case NOT_FOUND -> ResponseEntity.status(CouponErrorCode.COUPON_ISSUE_REQUEST_NOT_FOUND.getStatus())
                    .body(CouponErrorCode.COUPON_ISSUE_REQUEST_NOT_FOUND.getErrorResponse());
            case IN_PROGRESS -> ResponseEntity.ok(CustomResponse.onSuccess(CouponIssueRequestStatusResponse.inProgress()));
            // DONE: 신청 시점에 저장해둔 응답(JSON 문자열)과 그 HTTP 상태를 그대로 재현 — 본처리를 다시 태우지 않는다.
            case DONE -> ResponseEntity.status(result.responseStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result.responseBody());
        };
    }

    @GetMapping("/coupon-issues/{couponIssueId}/status")
    public CustomResponse<CouponIssueStatusResponse> getCouponIssueStatus(
            @PathVariable("couponIssueId") @Positive Long couponIssueId) {
        CouponIssueStatusResponse response = couponIssueQueryService.getStatus(couponIssueId);
        return CustomResponse.onSuccess(response);
    }

    @PostMapping("/coupon-issues/{couponIssueId}/use")
    public CustomResponse<Void> useCouponIssue(
            @PathVariable("couponIssueId") @Positive Long couponIssueId,
            @Valid @RequestBody CouponIssueUseRequest request
    ) {
        couponIssueUseService.use(couponIssueId, request.userId());
        return CustomResponse.onSuccess(null);
    }

    @PostMapping("/coupon-issues/{couponIssueId}/cancel")
    public CustomResponse<Void> cancelCouponIssue(
            @PathVariable("couponIssueId") @Positive Long couponIssueId,
            @Valid @RequestBody CouponIssueCancelRequest request
    ) {
        couponIssueCancelService.cancelUsage(couponIssueId, request.userId());
        return CustomResponse.onSuccess(null);
    }
}