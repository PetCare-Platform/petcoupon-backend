package com.mycom.petcoupon.idempotency.service;

/**
 * API 레벨 멱등성 처리 — "같은 Idempotency-Key로 온 요청은 한 번만 본처리한다"를 구현한다.
 * 구현체는 {@link IdempotencyKeyServiceImpl} 하나뿐이지만, 프로젝트 전체 컨벤션(Service/ServiceImpl 분리)에 맞춰 인터페이스를 둔다.
 */
public interface IdempotencyKeyService {

    /**
     * 컨트롤러가 본처리 시작 전에 제일 먼저 호출하는 진입점.
     * (user, idempotency_key)로 기존 시도를 찾아서, 상황에 맞는 IdempotencyDecision을 돌려준다.
     */
    IdempotencyDecision begin(Long userId, Long couponId, String idempotencyKey);

    // 본처리가 성공으로 끝났을 때 컨트롤러가 호출 — 실제 성공 응답을 그대로 저장해서 다음 재요청 때 재현한다.
    void succeed(Long recordId, int responseStatus, String responseBody);

    // 본처리가 "정상적으로 끝까지 실행됐지만" 실패로 끝났을 때(재고소진, 중복신청 등) 호출.
    void fail(Long recordId, int responseStatus, String responseBody);

    // Redis 등 인프라 예외로 본처리가 끊겼을 때 호출 — 응답 없이 FAILED 처리해서 재시도를 허용한다.
    void failWithoutBody(Long recordId);

    // 보관기간이 지난 행(상태 무관)을 정리한다. 삭제된 개수를 반환한다.
    int cleanupExpiredRecords();

    // GET 폴링 전용 순수 조회 — begin()과 달리 새 시도를 만들거나 상태를 바꾸지 않는다.
    IdempotencyKeyStatusResult findStatus(Long userId, String idempotencyKey);
}
