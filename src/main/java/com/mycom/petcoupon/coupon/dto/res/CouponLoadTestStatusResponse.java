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
        // [#200] Kafka 발행에 실제로 성공한 건수 — sent+consumed로만 계산하면 발행 후
        // 소비 실패(DLQ)로 빠진 건이 누락돼 "발행을 못 했다"처럼 보이는 문제가 있었다.
        // 정의는 IssueMessageRepository.countPublishedByCoupon() 참고.
        long published,
        long inProgressIdempotencyKeys,
        boolean overIssued,
        long duplicateUsers,
        boolean sequenceIntact,
        long elapsedSeconds
) {
}
