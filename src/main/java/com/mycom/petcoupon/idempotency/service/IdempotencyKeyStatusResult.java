package com.mycom.petcoupon.idempotency.service;

/**
 * IdempotencyKeyService.findStatus(...)가 GET 폴링 컨트롤러에게 돌려주는 결과값.
 * begin()의 IdempotencyDecision과 달리 새 시도를 만들거나 상태를 바꾸지 않는 순수 조회 전용이라 별도 타입으로 둔다.
 *
 *  - NOT_FOUND  : 이 (user, idempotencyKey) 조합으로 시도한 적이 없음 → 404
 *  - IN_PROGRESS: 아직 본처리 중이거나, 인프라 예외로 끊겨서 응답이 저장되지 않은 FAILED(재시도 대상) → 처리중 응답
 *  - DONE       : 본처리가 끝까지 실행돼 응답이 저장됨(SUCCEEDED, 또는 응답이 저장된 FAILED) → 그 응답을 그대로 재현
 */
public record IdempotencyKeyStatusResult(Type type, Integer responseStatus, String responseBody) {

    public enum Type { NOT_FOUND, IN_PROGRESS, DONE }

    public static IdempotencyKeyStatusResult notFound() {
        return new IdempotencyKeyStatusResult(Type.NOT_FOUND, null, null);
    }

    public static IdempotencyKeyStatusResult inProgress() {
        return new IdempotencyKeyStatusResult(Type.IN_PROGRESS, null, null);
    }

    public static IdempotencyKeyStatusResult done(Integer responseStatus, String responseBody) {
        return new IdempotencyKeyStatusResult(Type.DONE, responseStatus, responseBody);
    }
}
