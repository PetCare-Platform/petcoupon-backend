package com.mycom.petcoupon.reconciliation.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.config.CouponIssueRedisKeys;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {

    private final CouponRepository couponRepository;
    private final CouponStockRepository couponStockRepository;
    private final ReconciliationReportRepository reconciliationReportRepository;
    private final StringRedisTemplate redisTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public ReconciliationReport reconcile(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        // 발급이 아직 진행 중인 쿠폰은 Redis Stream/Outbox/Kafka가 드레인 안 끝난 상태라
        // STOCK_MISMATCH·SEQUENCE_GAP이 "아직 처리 중"인 것도 버그처럼 잡을 수 있다.
        // 발급 마감(ENDED) 후에만 이 배치가 신뢰할 수 있는 결과를 낸다.
        if (coupon.getStatus() != CouponStatus.ENDED) {
            throw new GeneralException(CouponErrorCode.RECONCILIATION_NOT_ALLOWED_YET);
        }

        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        LocalDateTime asOfAt = LocalDateTime.now();
        LocalDateTime startedAt = LocalDateTime.now();

        Integer redisRemaining = readRedisStock(couponId);
        long dbDlqCount = countDlqMessages(couponId);
        Long maxSequenceNo = findMaxSequenceNo(couponId);

        List<VerificationDetail> details = new java.util.ArrayList<>();
        details.addAll(findHistoryMismatches(couponId));
        details.addAll(findInvalidStatusTransitions(couponId));
        details.addAll(findDuplicateIssues(couponId));
        details.addAll(findStockMismatch(stock, redisRemaining));
        details.addAll(findSequenceGap(couponId, maxSequenceNo));
        details.addAll(findStockNotRestored(couponId));

        long totalCount = countTotalIssues(couponId);
        long distinctErrorIssueCount = details.stream()
                .map(VerificationDetail::getCouponIssueId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        Map<String, Long> statusCounts = countByStatus(couponId);
        long dbActiveCount = statusCounts.getOrDefault("ISSUED", 0L) + statusCounts.getOrDefault("USED", 0L);
        long dbExpiredCount = statusCounts.getOrDefault("EXPIRED", 0L);

        ReconciliationReport report = ReconciliationReport.builder()
                .coupon(coupon)
                .asOfAt(asOfAt)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .totalCount(totalCount)
                .successCount(totalCount - distinctErrorIssueCount)
                .errorCount(distinctErrorIssueCount)
                .stockTotal(stock.getTotalQuantity())
                .stockIssued(stock.getIssuedQuantity())
                .stockRemaining(stock.getRemainingQuantity())
                .dbActiveCount(dbActiveCount)
                .dbExpiredCount(dbExpiredCount)
                .dbDlqCount(dbDlqCount)
                .maxSequenceNo(maxSequenceNo)
                .redisRemaining(redisRemaining)
                .result(details.isEmpty() ? ReconciliationResult.MATCHED : ReconciliationResult.MISMATCHED)
                .build();

        details.forEach(d -> d.assignReport(report));
        return reconciliationReportRepository.save(report);
    }

    // 재고 키를 읽어 숫자로 바꾼다. 키가 없거나 숫자가 아니면 null — Lua가 재고 판정을 못 하는
    // STOCK_NOT_INITIALIZED 상태와 같은 뜻이라, STOCK_MISMATCH 쪽에서 그대로 불일치로 잡는다.
    private Integer readRedisStock(Long couponId) {
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

    private long countDlqMessages(Long couponId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM issue_message WHERE coupon_id = :couponId AND status = 'DLQ'")
                .setParameter("couponId", couponId)
                .getSingleResult()).longValue();
    }

    private Long findMaxSequenceNo(Long couponId) {
        Object result = entityManager.createNativeQuery(
                "SELECT MAX(sequence_no) FROM coupon_issue WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId)
                .getSingleResult();

        return result == null ? null : ((Number) result).longValue();
    }

    private long countTotalIssues(Long couponId) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId)
                .getSingleResult()).longValue();
    }

    private Map<String, Long> countByStatus(Long couponId) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT status, COUNT(*) FROM coupon_issue WHERE coupon_id = :couponId GROUP BY status
                """, Tuple.class)
                .setParameter("couponId", couponId)
                .getResultList();

        return rows.stream().collect(Collectors.toMap(
                row -> (String) row.get(0),
                row -> ((Number) row.get(1)).longValue()
        ));
    }

    // HISTORY_MISMATCH: 현재 status가 가장 최근 이력의 to_status와 다른 건
    private List<VerificationDetail> findHistoryMismatches(Long couponId) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT ci.coupon_issue_id, ci.user_id, ci.status, h.to_status
                  FROM coupon_issue ci
                  LEFT JOIN coupon_issue_history h
                    ON h.history_id = (
                         SELECT MAX(h2.history_id)
                           FROM coupon_issue_history h2
                          WHERE h2.coupon_issue_id = ci.coupon_issue_id
                       )
                 WHERE ci.coupon_id = :couponId
                   AND (h.to_status IS NULL OR ci.status <> h.to_status)
                """, Tuple.class)
                .setParameter("couponId", couponId)
                .getResultList();

        return rows.stream()
                .map(row -> VerificationDetail.builder()
                        .errorType(VerificationErrorType.HISTORY_MISMATCH)
                        .couponIssueId(((Number) row.get(0)).longValue())
                        .userId(((Number) row.get(1)).longValue())
                        .expectedValue(row.get(3) == null ? "이력 없음" : row.get(3).toString())
                        .actualValue(row.get(2).toString())
                        .message(row.get(3) == null ? "발급 이력이 없습니다" : "현재 상태와 최종 이력의 to_status가 다릅니다")
                        .build())
                .toList();
    }

    // INVALID_STATUS: 화이트리스트 밖의 (from_status, to_status) 조합
    private List<VerificationDetail> findInvalidStatusTransitions(Long couponId) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT h.coupon_issue_id, h.user_id, h.from_status, h.to_status
                  FROM coupon_issue_history h
                  JOIN coupon_issue ci ON ci.coupon_issue_id = h.coupon_issue_id
                 WHERE ci.coupon_id = :couponId
                   AND NOT (
                       (h.from_status = 'NONE'   AND h.to_status = 'ISSUED')  OR
                       (h.from_status = 'ISSUED' AND h.to_status = 'USED')    OR
                       (h.from_status = 'ISSUED' AND h.to_status = 'EXPIRED') OR
                       (h.from_status = 'USED'   AND h.to_status = 'ISSUED')
                   )
                """, Tuple.class)
                .setParameter("couponId", couponId)
                .getResultList();

        return rows.stream()
                .map(row -> VerificationDetail.builder()
                        .errorType(VerificationErrorType.INVALID_STATUS)
                        .couponIssueId(((Number) row.get(0)).longValue())
                        .userId(((Number) row.get(1)).longValue())
                        .expectedValue("허용된 전이 목록 내 값")
                        .actualValue(row.get(2) + " -> " + row.get(3))
                        .message("허용되지 않은 상태 전이입니다")
                        .build())
                .toList();
    }

    // DUPLICATE_ISSUE: 동일 (coupon_id, user_id) 중복 발급 — 중복에 해당하는 발급 건 하나하나를 반환
    private List<VerificationDetail> findDuplicateIssues(Long couponId) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT coupon_issue_id, user_id
                  FROM coupon_issue
                 WHERE coupon_id = :couponId
                   AND user_id IN (
                       SELECT user_id FROM coupon_issue
                        WHERE coupon_id = :couponId
                        GROUP BY user_id
                       HAVING COUNT(*) > 1
                   )
                """, Tuple.class)
                .setParameter("couponId", couponId)
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
    private List<VerificationDetail> findStockMismatch(CouponStock stock, Integer redisRemaining) {
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
    private List<VerificationDetail> findSequenceGap(Long couponId, Long maxSequenceNo) {
        if (maxSequenceNo == null) {
            return List.of();
        }

        long distinctCount = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(DISTINCT sequence_no) FROM coupon_issue WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId)
                .getSingleResult()).longValue();

        if (distinctCount >= maxSequenceNo) {
            return List.of();
        }

        long missingCount = maxSequenceNo - distinctCount;
        Map<String, Long> messageCounts = countIssueMessagesByStatus(couponId, "DLQ", "FAILED");

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

    // STOCK_NOT_RESTORED: 재시도를 다 소진하고 최종 실패(DLQ)한 요청 — Lua가 예약해둔 재고 1개를
    // 되돌려주는 restoreStock()이 아직 없어서, DLQ row 하나하나가 곧 미복구 재고 1개다.
    private List<VerificationDetail> findStockNotRestored(Long couponId) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT message_id, user_id FROM issue_message
                 WHERE coupon_id = :couponId AND status = 'DLQ'
                """, Tuple.class)
                .setParameter("couponId", couponId)
                .getResultList();

        return rows.stream()
                .map(row -> VerificationDetail.builder()
                        .errorType(VerificationErrorType.STOCK_NOT_RESTORED)
                        .userId(((Number) row.get(1)).longValue())
                        .expectedValue("재고 복구됨")
                        .actualValue("DLQ 확정 (message_id=" + row.get(0) + ")")
                        .message("최종 실패했지만 예약된 재고가 복구되지 않았습니다")
                        .build())
                .toList();
    }

    private Map<String, Long> countIssueMessagesByStatus(Long couponId, String... statuses) {
        List<Tuple> rows = entityManager.createNativeQuery("""
                SELECT status, COUNT(*) FROM issue_message
                 WHERE coupon_id = :couponId AND status IN (:statuses)
                 GROUP BY status
                """, Tuple.class)
                .setParameter("couponId", couponId)
                .setParameter("statuses", List.of(statuses))
                .getResultList();

        return rows.stream().collect(Collectors.toMap(
                row -> (String) row.get(0),
                row -> ((Number) row.get(1)).longValue()
        ));
    }
}