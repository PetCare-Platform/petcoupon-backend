package com.mycom.petcoupon.idempotency.service;

import java.util.Optional;

/**
 * requestId <-> idempotency_key.idempotency_id 상호 변환.
 * 발급 신청 API가 requestId를 "issue:{idempotencyId}" 형식으로 만들어 큐에 보낸다(전역 유니크 보장 목적,
 * idempotency_key는 (user_id, key) 스코프라 서로 다른 유저가 같은 값을 보낼 수 있어서 그대로는 못 씀).
 * Stream Consumer/Kafka Consumer는 이 형식을 그대로 파싱해서 어느 idempotency_key row를 갱신해야 하는지 찾는다.
 *
 * 이 형식이 아닌 requestId도 존재한다 — CouponIssueStreamProducer를 직접 호출하는 경로(통합 테스트 등)는
 * idempotency_key를 아예 거치지 않으므로, 그런 요청까지 강제로 이 형식이길 기대하면 안 된다.
 */
public final class IdempotencyRequestIdCodec {

    private static final String PREFIX = "issue:";

    private IdempotencyRequestIdCodec() {
    }

    public static String encode(Long idempotencyId) {
        return PREFIX + idempotencyId;
    }

    // requestId가 반드시 이 형식이어야 하는 게 확실한 자리에서만 쓴다 — 아니면 tryDecode를 쓸 것.
    public static Long decode(String requestId) {
        return tryDecode(requestId)
                .orElseThrow(() -> new IllegalStateException("requestId가 idempotency_id 기반 형식이 아닙니다. requestId=" + requestId));
    }

    // idempotency_key를 거치지 않고 들어온 requestId일 수도 있는 자리에서 쓴다 — 형식이 아니면 빈 Optional.
    public static Optional<Long> tryDecode(String requestId) {
        if (requestId == null || !requestId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(requestId.substring(PREFIX.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
