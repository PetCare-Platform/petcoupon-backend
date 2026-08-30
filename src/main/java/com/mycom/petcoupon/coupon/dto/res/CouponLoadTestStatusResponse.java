package com.mycom.petcoupon.coupon.dto.res;

import lombok.Builder;

// 부하 테스트 현황 조회(#195) — 대시보드 1줄 카드 + 2줄 깔때기가 전부 이 응답 하나로 그려진다.
// load-test/sql/verify_issue_result.sql을 그대로 서비스로 옮긴 값들이라, 필드별 근거는
// 그 파일의 블록 번호를 참고(CouponLoadTestStatusServiceImpl 주석).
@Builder
public record CouponLoadTestStatusResponse(
        long accepted,
        long passed,
        // 깔때기 "재고 통과" 전용 — Redis Lua 가 실제로 통과시킨 건수(totalQuantity - Redis 재고)다.
        // passed 는 coupon_issue 를 세므로 파이프라인 맨 끝(DB 확정) 시점 값이고, 상류인 재고 통과
        // 자리에 쓰면 Kafka 발행보다 작아지는 역전이 생긴다. 판정(overIssued)과 손실 계산은
        // 확정된 발급 수여야 하므로 passed 를 그대로 쓰고, 이 값은 화면 전용이다.
        long stockPassed,
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
