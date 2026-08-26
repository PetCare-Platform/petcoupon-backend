package com.mycom.petcoupon.coupon.dto.res;

/**
 * GET .../coupon-issue-requests/status 폴링에서 아직 최종 결과가 없을 때(IN_PROGRESS)만 쓰는 응답.
 * 결과가 이미 나온 경우(SUCCEEDED/FAILED)는 idempotency_key에 저장된 원본 응답을 그대로 재현하므로
 * 이 DTO를 거치지 않는다 — CouponIssueController.getCouponIssueRequestStatus 참고.
 */
public record CouponIssueRequestStatusResponse(String status) {

    public static CouponIssueRequestStatusResponse inProgress() {
        return new CouponIssueRequestStatusResponse("IN_PROGRESS");
    }
}
