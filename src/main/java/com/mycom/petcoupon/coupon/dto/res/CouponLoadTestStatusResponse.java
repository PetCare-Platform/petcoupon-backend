package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

// 부하 테스트 현황 조회(#195) — 대시보드 1줄 카드 + 2줄 깔때기가 전부 이 응답 하나로 그려진다.
// load-test/sql/verify_issue_result.sql을 그대로 서비스로 옮긴 값들이라, 필드별 근거는
// 그 파일의 블록 번호를 참고(CouponLoadTestStatusServiceImpl 주석).
@Builder
public record CouponLoadTestStatusResponse(
        long accepted,
        long passed,
        long rejected,
        long pending,
        long sent,
        long consumed,
        long failed,
        long dlq,
        long inProgressIdempotencyKeys,
        boolean overIssued,
        long duplicateUsers,
        boolean sequenceIntact,
        long elapsedSeconds
) {
}
