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

    // 부하 테스트 현황 조회(#195)용 — "202를 돌려받았는가" 기준 접수 수. 행이 아니라 고유
    // user_id 수를 센다(1인 1매라 같은 유저의 중복 신청은 한 건으로 잡아야 발급 건수와 비교가
    // 맞는다). [PR #195 리뷰 반영] 처음엔 verify_issue_result.sql 1번 블록 조건(NOT(FAILED AND
    // responseStatus IS NULL))을 그대로 썼는데, 그건 "부하가 끝난 뒤"(IN_PROGRESS가 안 남는다는
    // 전제)에만 맞는 조건이라 도중 폴링하면 아직 202도 못 받은 IN_PROGRESS 행까지 잡혔다.
    // responseStatus IS NOT NULL이면 "실제로 응답이 기록된 행"만 세서 이 문제와 무응답 실패
    // (failWithoutBody) 제외를 한 조건으로 같이 푼다.
    @Query("""
            SELECT COUNT(DISTINCT ik.user.userId) FROM IdempotencyKey ik
             WHERE ik.coupon.couponId = :couponId
               AND ik.responseStatus IS NOT NULL
            """)
    long countAcceptedByCouponId(@Param("couponId") Long couponId);

    // [PR #195 리뷰 반영] rejected를 accepted - passed로 빼서 구하면 두 가지가 깨진다 —
    // (1) 부하 중엔 아직 pending/SENT인(파이프라인 처리 중) 요청까지 "거절"로 잡히고,
    // (2) 초과발급처럼 passed가 accepted보다 크면 음수가 나온다(테스트 참고). 최종 FAILED
    // 확정 건만 직접 세면 둘 다 자연히 해결된다 — 처리 중인 요청은 아직 FAILED가 아니라
    // 안 잡히고, 뺄셈이 아니라 COUNT라 음수가 나올 수 없다.
    long countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(Long couponId, IdempotencyStatus status);

    // 응답을 못 받고 끊긴 요청 수(verify_issue_result.sql 13번 블록과 동일 기준).
    long countByCoupon_CouponIdAndStatus(Long couponId, IdempotencyStatus status);

    // 실패 사유 분류 집계(#195) — rejections. response_body가 GeneralException을 그대로 저장한
    // CustomResponse.onFailure(errorCode) JSON이라 code 필드로 사유를 가른다(CouponController.issue,
    // CouponIssueServiceImpl 참고). EVENT_NOT_OPEN/EVENT_CLOSED는 여기서 못 잡는다 — 그 두 사유는
    // 컨트롤러가 멱등키 등록 전에 Fail-Fast로 끝내버려서 이 테이블에 행 자체가 없다.
    // idx_idem_coupon_status(coupon_id, status)가 coupon_id+status 필터를 커버해서 JSON 파싱은
    // 그 결과 행에만 실행된다.
    @Query(value = """
            SELECT
                   COALESCE(SUM(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(response_body, '$.code')) = :soldOutCode THEN 1 ELSE 0 END), 0) AS soldOut,
                   COALESCE(SUM(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(response_body, '$.code')) = :duplicateUserCode THEN 1 ELSE 0 END), 0) AS alreadyIssued
              FROM idempotency_key
             WHERE coupon_id = :couponId
               AND status = 'FAILED'
               AND response_body IS NOT NULL
            """, nativeQuery = true)
    IdempotencyRejectionCounts countRejectionsByCouponId(
            @Param("couponId") Long couponId,
            @Param("soldOutCode") String soldOutCode,
            @Param("duplicateUserCode") String duplicateUserCode
    );
}
