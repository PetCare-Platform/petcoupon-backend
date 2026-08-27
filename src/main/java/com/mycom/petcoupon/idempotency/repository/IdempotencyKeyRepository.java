package com.mycom.petcoupon.idempotency.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    // (user, idempotency_key) 유니크 조합으로 기존 시도를 찾는다 — 멱등성 판단의 시작점(IdempotencyKeyService.begin 참고)
    Optional<IdempotencyKey> findByUser_UserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // 보관기간이 지난 행을 정리하기 위한 삭제 — 상태 무관하게 생성 시각 기준으로 지운다
    // (IdempotencyKeyServiceImpl.cleanupExpiredRecords 참고)
    int deleteByCreatedAtBefore(LocalDateTime threshold);

    // IdempotencyKeyServiceImpl.succeed()가 findById 후 엔티티를 조작하는 read-then-write 방식이면,
    // HTTP 스레드(202 잠정 응답 저장)와 Consumer(200 최종 확정)가 동시에 호출될 때 레이스가 생긴다 —
    // HTTP 스레드가 IN_PROGRESS인 걸 읽은 직후 Consumer가 먼저 200으로 커밋해도, 그 사실을 모른 채
    // HTTP 스레드가 뒤늦게 202로 덮어쓸 수 있다. provisional=true(202처럼 "아직 확정 아님"인 쓰기)일
    // 때만 status=:expectedStatus(IN_PROGRESS) 조건을 걸어 원자적으로 막는다 — claimForAbandon/
    // increaseIssuedQuantity와 같은 조건부 UPDATE 패턴. provisional=false(진짜 최종 결과)면 조건 없이
    // 항상 반영한다.
    //
    // clearAutomatically는 지정하지 않는다(기본 false) — succeed()는 CouponIssuePersister.persist()가
    // 자기 자신을 호출하는 confirmIdempotencySucceeded() 안에서, coupon_issue/coupon_issue_history
    // insert와 같은 트랜잭션으로 실행된다. clearAutomatically=true를 쓰면 그 트랜잭션에서 아직
    // 플러시되지 않은 다른 엔티티 변경분이 이 벌크 UPDATE 직후 영속성 컨텍스트 clear로 유실될 수
    // 있다(IssueMessageRepository.updateStatusByMessageKey의 주석과 같은 이유 — 그쪽도 같은
    // persist() 트랜잭션 안에서 이 메서드를 예로 들어 명시적으로 clearAutomatically를 뺐다).
    @Transactional
    @Modifying
    @Query("""
            UPDATE IdempotencyKey ik
               SET ik.status = :status,
                   ik.responseStatus = :responseStatus,
                   ik.responseBody = :responseBody
             WHERE ik.idempotencyId = :recordId
               AND (:provisional = false OR ik.status = :expectedStatus)
            """)
    int completeIfAllowed(
            @Param("recordId") Long recordId,
            @Param("status") IdempotencyStatus status,
            @Param("responseStatus") Integer responseStatus,
            @Param("responseBody") String responseBody,
            @Param("provisional") boolean provisional,
            @Param("expectedStatus") IdempotencyStatus expectedStatus
    );
}
