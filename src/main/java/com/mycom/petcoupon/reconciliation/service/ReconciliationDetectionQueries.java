package com.mycom.petcoupon.reconciliation.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.issue.config.CouponIssueRedisKeys;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;

/**
 * 정합성 검증 쿼리 로직 모음. reconciliation.batch 패키지의 Tasklet들(ReportInitTasklet,
 * RemainingChecksTasklet)이 공유한다 — 검증 "규칙"은 어느 Step에서 호출하든 하나로 유지한다.
 *
 * findHistoryMismatches/findInvalidStatusTransitions는 여기 없다 — 대용량이라 Batch에서는
 * 전체 로드(getResultList) 대신 페이징 Reader로 따로 구현한다(ReconciliationJobConfig 참고).
 *
 * asOfAt에 대한 재현성 한계 — 재현성은 "그 시점에 존재했던 행"까지만 보장한다(created_at
 * &lt;= asOfAt). coupon_issue.status처럼 덮어써지는 값 자체를 그 시점 값으로 복원하지는
 * 않는다(예: asOfAt 이후 취소된 건도 조회 시점의 최신 status로 보인다) — 완전한 시점 복원을
 * 하려면 상태를 coupon_issue_history로부터 재구성해야 하는데, ENDED+파이프라인 드레인 완료
 * 전제상 정상 케이스에서는 차이가 거의 없어 오늘 범위에서는 하지 않는다.
 *
 * Redis(readRedisStock)와 CouponStock의 실시간 필드는 asOfAt으로 필터링할 방법이 없다 —
 * 둘 다 "현재 값"만 조회 가능한 라이브 스토어라, 이 두 검증(STOCK_MISMATCH)은 재현성이 아니라
 * "호출 시점의 라이브 값 비교"라는 걸 알고 써야 한다. ENDED+드레인 완료 후에는 두 값이
 * 정지해 있어야 하므로 실질적으로는 안전하다.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationDetectionQueries {

    private final StringRedisTemplate redisTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    // 재고 키를 읽어 숫자로 바꾼다. 키가 없거나 숫자가 아니면 null — Lua가 재고 판정을 못 하는
    // STOCK_NOT_INITIALIZED 상태와 같은 뜻이라, STOCK_MISMATCH 쪽에서 그대로 불일치로 잡는다.
    // Redis는 시점 조회가 안 되는 라이브 스토어라 asOfAt을 받지 않는다(클래스 주석 참고).
    public Integer readRedisStock(Long couponId) {
        String raw = redisTemplate.opsForValue().get(CouponIssueRedisKeys.stock(couponId));

        if (raw == null) {
            return null;
        }

        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public long countDlqMessages(Long couponId, LocalDateTime asOfAt) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM issue_message WHERE coupon_id = :couponId AND status = 'DLQ' AND created_at <= :asOfAt")
                .setParameter("couponId", couponId)
                .setParameter("asOfAt", asOfAt)
                .getSingleResult()).longValue();
    }

    public Long findMaxSequenceNo(Long couponId, LocalDateTime asOfAt) {
        Object result = entityManager.createNativeQuery(
                "SELECT MAX(sequence_no) FROM coupon_issue WHERE coupon_id = :couponId AND created_at <= :asOfAt")
                .setParameter("couponId", couponId)
                .setParameter("asOfAt", asOfAt)
                .getSingleResult();

        return result == null ? null : ((Number) result).longValue();
    }

    public long countTotalIssues(Long couponId, LocalDateTime asOfAt) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = :couponId AND created_at <= :asOfAt")
                .setParameter("couponId", couponId)
                .setParameter("asOfAt", asOfAt)
                .getSingleResult()).longValue();
    }

    public Map<String, Long> countByStatus(Long couponId, LocalDateTime asOfAt) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT status, COUNT(*) FROM coupon_issue
                 WHERE coupon_id = :couponId AND created_at <= :asOfAt
                 GROUP BY status
                """, Tuple.class)
                .setParameter("couponId", couponId)
                .setParameter("asOfAt", asOfAt)
                .getResultList();

        return rows.stream().collect(Collectors.toMap(
                row -> (String) row.get(0),
                row -> ((Number) row.get(1)).longValue()
        ));
    }

    // DUPLICATE_ISSUE: 동일 (coupon_id, user_id) 중복 발급 — 중복에 해당하는 발급 건 하나하나를 반환
    public List<VerificationDetail> findDuplicateIssues(Long couponId, LocalDateTime asOfAt) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT coupon_issue_id, user_id
                  FROM coupon_issue
                 WHERE coupon_id = :couponId
                   AND created_at <= :asOfAt
                   AND user_id IN (
                       SELECT user_id FROM coupon_issue
                        WHERE coupon_id = :couponId
                          AND created_at <= :asOfAt
                        GROUP BY user_id
                       HAVING COUNT(*) > 1
                   )
                """, Tuple.class)
                .setParameter("couponId", couponId)
                .setParameter("asOfAt", asOfAt)
                .getResultList();

        return rows.stream()
                .map(row -> VerificationDetail.builder()
                        .errorType(VerificationErrorType.DUPLICATE_ISSUE)
                        .couponIssueId(((Number) row.get(0)).longValue())
                        .userId(((Number) row.get(1)).longValue())
                        .expectedValue("1")
                        .actualValue("중복 발급")
                        .message("동일 유저에게 중복 발급된 이력이 있습니다")
                        .build())
                .toList();
    }

    // STOCK_MISMATCH: DB coupon_stock.remaining_quantity 와 Redis 재고 키 값이 다른 경우.
    // 쿠폰 전체에 대한 문제라 특정 발급 건을 가리키지 않으므로 couponIssueId/userId는 비운다.
    // 둘 다 라이브 값이라 asOfAt을 받지 않는다(클래스 주석 참고).
    public List<VerificationDetail> findStockMismatch(CouponStock stock, Integer redisRemaining) {
        if (redisRemaining != null && redisRemaining == stock.getRemainingQuantity()) {
            return List.of();
        }

        return List.of(VerificationDetail.builder()
                .errorType(VerificationErrorType.STOCK_MISMATCH)
                .expectedValue(String.valueOf(stock.getRemainingQuantity()))
                .actualValue(redisRemaining == null ? "Redis 키 없음" : String.valueOf(redisRemaining))
                .message("DB 재고와 Redis 재고가 일치하지 않습니다")
                .build());
    }

    // SEQUENCE_GAP: coupon_issue.sequence_no가 1부터 연속이어야 하는데 중간에 비어있는 경우.
    // Lua가 순번은 내줬지만(재고 예약) 그 요청이 DB에 끝내 반영되지 않았다는 뜻 — 몇 번이 빈 건지까지는
    // 1차 버전에서 찾지 않고, 참고용으로 이 쿠폰의 DLQ/FAILED(재시도중) 건수를 같이 보여준다.
    public List<VerificationDetail> findSequenceGap(Long couponId, Long maxSequenceNo, LocalDateTime asOfAt) {
        if (maxSequenceNo == null) {
            return List.of();
        }

        long distinctCount = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(DISTINCT sequence_no) FROM coupon_issue WHERE coupon_id = :couponId AND created_at <= :asOfAt")
                .setParameter("couponId", couponId)
                .setParameter("asOfAt", asOfAt)
                .getSingleResult()).longValue();

        if (distinctCount >= maxSequenceNo) {
            return List.of();
        }

        long missingCount = maxSequenceNo - distinctCount;
        Map<String, Long> messageCounts = countIssueMessagesByStatus(couponId, asOfAt, "DLQ", "FAILED");

        return List.of(VerificationDetail.builder()
                .errorType(VerificationErrorType.SEQUENCE_GAP)
                .expectedValue("1~" + maxSequenceNo + " 연속")
                .actualValue(distinctCount + "건 존재, " + missingCount + "개 번호 없음")
                .message(
                        "순번에 빈 구간이 있습니다 (참고: 이 쿠폰의 issue_message DLQ "
                                + messageCounts.getOrDefault("DLQ", 0L) + "건, FAILED(재시도중) "
                                + messageCounts.getOrDefault("FAILED", 0L) + "건)"
                )
                .build());
    }

    public Map<String, Long> countIssueMessagesByStatus(Long couponId, LocalDateTime asOfAt, String... statuses) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT status, COUNT(*) FROM issue_message
                 WHERE coupon_id = :couponId AND status IN (:statuses) AND created_at <= :asOfAt
                 GROUP BY status
                """, Tuple.class)
                .setParameter("couponId", couponId)
                .setParameter("statuses", List.of(statuses))
                .setParameter("asOfAt", asOfAt)
                .getResultList();

        return rows.stream().collect(Collectors.toMap(
                row -> (String) row.get(0),
                row -> ((Number) row.get(1)).longValue()
        ));
    }
}
