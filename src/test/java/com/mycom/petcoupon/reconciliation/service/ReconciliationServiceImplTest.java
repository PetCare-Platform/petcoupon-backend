package com.mycom.petcoupon.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 네이티브 SQL(HISTORY_MISMATCH/INVALID_STATUS 탐지 쿼리)은 컴파일 시점에 검증되지 않으므로 실 DB로 확인한다.
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 *
 * DUPLICATE_ISSUE는 uk_issue_coupon_user(coupon_id, user_id) UNIQUE 제약 때문에
 * 일반적인 persist로는 재현이 안 돼서 긍정 케이스 테스트는 생략한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReconciliationServiceImpl.class)
class ReconciliationServiceImplTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ReconciliationServiceImpl reconciliationService;

    @Autowired
    private ReconciliationReportRepository reconciliationReportRepository;

    private Coupon coupon;

    @BeforeEach
    void setUp() {
        AppUser admin = AppUser.builder()
                .name("관리자").email("recon-admin@test.com").phone("010-0000-0000")
                .role(UserRole.ROLE_ADMIN).build();
        entityManager.persist(admin);

        Event event = Event.builder()
                .createdBy(admin).name("정합성 테스트 이벤트").description("d")
                .openAt(LocalDateTime.now()).closeAt(LocalDateTime.now().plusDays(1)).build();
        entityManager.persist(event);

        coupon = Coupon.builder()
                .event(event).name("정합성 테스트 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1000).minOrderAmount(1000)
                .issueStartAt(LocalDateTime.now()).issueEndAt(LocalDateTime.now().plusDays(1))
                .validDays(7).build();
        entityManager.persist(coupon);

        CouponStock stock = CouponStock.builder().coupon(coupon).totalQuantity(10).build();
        entityManager.persist(stock);

        entityManager.flush();
    }

    @Test
    void 불일치가_없으면_MATCHED로_기록한다() {
        createIssueWithHistory(IssueStatus.ISSUED, "OK-1", "NONE", "ISSUED");

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getResult()).isEqualTo(ReconciliationResult.MATCHED);
        assertThat(report.getErrorCount()).isZero();
        assertThat(report.getSuccessCount()).isEqualTo(1L);
    }

    @Test
    void 현재_상태와_최종_이력이_다르면_HISTORY_MISMATCH를_탐지한다() {
        // status는 USED인데 이력은 ISSUED까지만 남겨서 불일치를 만듦
        createIssueWithHistory(IssueStatus.USED, "MISMATCH-1", "NONE", "ISSUED");

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getResult()).isEqualTo(ReconciliationResult.MISMATCHED);
        assertThat(report.getVerificationDetails())
                .anyMatch(d -> d.getErrorType() == VerificationErrorType.HISTORY_MISMATCH);
    }

    @Test
    void 발급_이력이_아예_없으면_HISTORY_MISMATCH를_탐지한다() {
        createIssueWithoutHistory(IssueStatus.ISSUED, "NO-HISTORY-1");

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getResult()).isEqualTo(ReconciliationResult.MISMATCHED);
        assertThat(report.getVerificationDetails())
                .anyMatch(d -> d.getErrorType() == VerificationErrorType.HISTORY_MISMATCH
                        && "이력 없음".equals(d.getExpectedValue()));
    }

    @Test
    void 허용되지_않은_상태_전이_이력이면_INVALID_STATUS를_탐지한다() {
        CouponIssue issue = createIssueWithHistory(IssueStatus.EXPIRED, "INVALID-1", "NONE", "ISSUED");
        // 화이트리스트에 없는 전이(EXPIRED -> USED)를 추가로 남김 — 이러면 HISTORY_MISMATCH도 같이 걸림
        insertHistory(issue, "EXPIRED", "USED");

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getVerificationDetails())
                .anyMatch(d -> d.getErrorType() == VerificationErrorType.INVALID_STATUS);

        // 같은 발급 건이 두 항목(HISTORY_MISMATCH + INVALID_STATUS)에 동시에 걸려도,
        // errorCount는 발급 건 단위로 1건만 세야 하고, totalCount = successCount + errorCount가 항상 성립해야 함
        assertThat(report.getErrorCount()).isEqualTo(1L);
        assertThat(report.getTotalCount()).isEqualTo(report.getSuccessCount() + report.getErrorCount());
    }

    private ReconciliationReport latestReport() {
        entityManager.clear();
        return reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .findFirst()
                .orElseThrow();
    }

    private CouponIssue createIssueWithHistory(IssueStatus status, String couponCode, String from, String to) {
        CouponIssue issue = createIssueWithoutHistory(status, couponCode);
        insertHistory(issue, from, to);
        return issue;
    }

    private CouponIssue createIssueWithoutHistory(IssueStatus status, String couponCode) {
        AppUser user = AppUser.builder()
                .name("유저-" + couponCode).email(couponCode + "@test.com").phone("010-1111-1111")
                .role(UserRole.ROLE_MEMBER).build();
        entityManager.persist(user);

        CouponIssue issue = CouponIssue.builder()
                .coupon(coupon).user(user).sequenceNo(1)
                .couponCode(couponCode).requestId("req-" + couponCode)
                .status(status)
                .usedAt(status == IssueStatus.USED ? LocalDateTime.now() : null)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        entityManager.persist(issue);
        entityManager.flush();
        return issue;
    }

    private void insertHistory(CouponIssue issue, String from, String to) {
        entityManager.createNativeQuery("""
                INSERT INTO coupon_issue_history
                    (coupon_issue_id, coupon_id, user_id, from_status, to_status, actor_type, created_at)
                VALUES (:issueId, :couponId, :userId, :from, :to, 'SYSTEM', NOW(6))
                """)
                .setParameter("issueId", issue.getCouponIssueId())
                .setParameter("couponId", coupon.getCouponId())
                .setParameter("userId", issue.getUser().getUserId())
                .setParameter("from", from)
                .setParameter("to", to)
                .executeUpdate();
    }
}
