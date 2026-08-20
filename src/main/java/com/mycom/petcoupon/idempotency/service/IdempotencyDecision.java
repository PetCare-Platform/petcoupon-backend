package com.mycom.petcoupon.idempotency.service;

/**
 * IdempotencyKeyService.begin(...)이 컨트롤러에게 "다음에 뭘 해야 하는지" 알려주는 결과값.
 * 컨트롤러는 이 4가지 타입만 보고 분기하면 되고, 상태머신의 세부 판단(만료/재현 여부 등)은 몰라도 된다.
 *
 *  - PROCEED   : 처음 보는 요청이거나 죽은 시도를 이어받음 → 본처리(CouponIssueService) 진행하고,
 *                끝나면 recordId로 succeed()/fail()/failWithoutBody() 중 하나를 호출해서 결과를 기록해야 함
 *  - REPLAY    : 이미 끝난 시도(SUCCEEDED, 또는 응답이 저장된 FAILED) → 저장된 응답 그대로 반환, 본처리 재실행 안 함
 *  - CONFLICT  : 아직 처리 중(만료 안 됨)인 시도 → 409로 "처리 중이니 기다려라" 안내
 *  - KEY_REUSED: 같은 idempotency_key인데 요청 내용(coupon/user)이 다름 → 409로 "다른 요청엔 새 키 써라" 안내
 */
public record IdempotencyDecision(Type type, Long recordId, Integer replayStatus, String replayBody) {

    public enum Type { PROCEED, REPLAY, CONFLICT, KEY_REUSED }

    public static IdempotencyDecision proceed(Long recordId) {
        return new IdempotencyDecision(Type.PROCEED, recordId, null, null);
    }

    public static IdempotencyDecision replay(Integer status, String body) {
        return new IdempotencyDecision(Type.REPLAY, null, status, body);
    }

    public static IdempotencyDecision conflict() {
        return new IdempotencyDecision(Type.CONFLICT, null, null, null);
    }

    public static IdempotencyDecision keyReused() {
        return new IdempotencyDecision(Type.KEY_REUSED, null, null, null);
    }
}
