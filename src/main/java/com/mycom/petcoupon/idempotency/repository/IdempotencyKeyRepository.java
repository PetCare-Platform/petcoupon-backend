package com.mycom.petcoupon.idempotency.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    // (user, idempotency_key) 유니크 조합으로 기존 시도를 찾는다 — 멱등성 판단의 시작점(IdempotencyKeyService.begin 참고)
    Optional<IdempotencyKey> findByUser_UserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // 보관기간이 지난 행을 정리하기 위한 삭제 — 상태 무관하게 생성 시각 기준으로 지운다
    // (IdempotencyKeyServiceImpl.cleanupExpiredRecords 참고)
    int deleteByCreatedAtBefore(LocalDateTime threshold);
}
