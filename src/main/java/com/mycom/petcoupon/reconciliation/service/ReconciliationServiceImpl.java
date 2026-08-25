package com.mycom.petcoupon.reconciliation.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
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
    private final ReconciliationReportRepository reconciliationReportRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void reconcile(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        LocalDateTime asOfAt = LocalDateTime.now();
        LocalDateTime startedAt = LocalDateTime.now();

        List<VerificationDetail> details = new java.util.ArrayList<>();
        details.addAll(findHistoryMismatches(couponId));
        details.addAll(findInvalidStatusTransitions(couponId));
        details.addAll(findDuplicateIssues(couponId));

        long totalCount = countTotalIssues(couponId);
        long distinctErrorIssueCount = details.stream()
                .map(VerificationDetail::getCouponIssueId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        Map<String, Long> statusCounts = countByStatus(couponId);
        long dbActiveCount = statusCounts.getOrDefault("ISSUED", 0L) + statusCounts.getOrDefault("USED", 0L);
        long dbCanceledCount = statusCounts.getOrDefault("CANCELED", 0L);
        long dbExpiredCount = statusCounts.getOrDefault("EXPIRED", 0L);

        ReconciliationReport report = ReconciliationReport.builder()
                .coupon(coupon)
                .asOfAt(asOfAt)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .totalCount(totalCount)
                .successCount(totalCount - distinctErrorIssueCount)
                .errorCount(distinctErrorIssueCount)
                .stockTotal(null)
                .stockIssued(null)
                .stockRemaining(null)
                .dbActiveCount(dbActiveCount)
                .dbCanceledCount(dbCanceledCount)
                .dbExpiredCount(dbExpiredCount)
                .dbDlqCount(null)
                .maxSequenceNo(null)
                .redisRemaining(null)
                .result(details.isEmpty() ? ReconciliationResult.MATCHED : ReconciliationResult.MISMATCHED)
                .build();

        details.forEach(d -> d.assignReport(report));
        reconciliationReportRepository.save(report);
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
                       (h.from_status = 'ISSUED' AND h.to_status = 'CANCELED') OR
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
}