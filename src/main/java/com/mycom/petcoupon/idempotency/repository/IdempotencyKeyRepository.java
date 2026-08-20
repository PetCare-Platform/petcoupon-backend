package com.mycom.petcoupon.idempotency.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    // (user, idempotency_key) 유니크 조합으로 기존 시도를 찾는다 — 멱등성 판단의 시작점(IdempotencyKeyService.begin 참고)
    Optional<IdempotencyKey> findByUser_UserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
