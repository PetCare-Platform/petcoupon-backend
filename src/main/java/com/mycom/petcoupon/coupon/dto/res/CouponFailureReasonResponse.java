package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

// 실패 사유 분류 집계(#195). rejections는 EVENT_NOT_OPEN/EVENT_CLOSED를 뺀 2개뿐이다 —
// 그 둘은 CouponController가 멱등키 등록 전에 Fail-Fast로 끝내버려서 idempotency_key에
// 아예 저장되지 않는다(CouponFailureReasonServiceImpl 주석 참고). failures도 요청받은
// 5개 대신 실제 코드에 있는 발생 지점 2개(IssueFailureReason)로만 분류한다.
@Builder
public record CouponFailureReasonResponse(
        Rejections rejections,
        Failures failures
) {
    @Builder
    public record Rejections(long soldOut, long alreadyIssued) {
    }

    @Builder
    public record Failures(long kafkaPublishFailed, long consumeProcessingFailed) {
    }
}
