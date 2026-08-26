package com.mycom.petcoupon.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * ReconciliationServiceImpl(동기 API)이 죽은 코드가 되어 삭제되면서, 그 테스트에만 남아있던
 * DUPLICATE_ISSUE/SEQUENCE_GAP/STOCK_NOT_RESTORED 회귀 테스트를 여기로 옮긴다 — 검증 "규칙"
 * 자체는 ReconciliationDetectionQueries에 있으니 이 클래스를 직접 때리는 게 맞다.
 *
 * DUPLICATE_ISSUE는 옮길 긍정 케이스가 원래 없었다 — coupon_issue의 uk_issue_coupon_user
 * (coupon_id, user_id) UNIQUE 제약 때문에 실 스키마로는 중복 자체를 만들 수 없어서(네이티브
 * INSERT로도 DB가 거부한다) 기존 ReconciliationServiceImplTest도 긍정 케이스를 생략했었다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReconciliationDetectionQueries.class)
class ReconciliationDetectionQueriesTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ReconciliationDetectionQueries queries;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private Coupon coupon;
    private int sequenceCounter = 0;
    private int messageSequenceCounter = 900;

    @BeforeEach
    void setUp() {
        AppUser admin = AppUser.builder()
                .name("쿼리테스트 관리자").email("detection-queries@test.com").phone("010-0000-0000")
                .role(UserRole.ROLE_ADMIN).build();
        entityManager.persist(admin);

        Event event = Event.builder()
                .createdBy(admin).name("쿼리테스트 이벤트").description("d")
                .openAt(LocalDateTime.now()).closeAt(LocalDateTime.now().plusDays(1)).build();
        entityManager.persist(event);

        coupon = Coupon.builder()
                .event(event).name("쿼리테스트 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1000).minOrderAmount(1000)
                .issueStartAt(LocalDateTime.now()).issueEndAt(LocalDateTime.now().plusDays(1))
                .validDays(7).build();
        entityManager.persist(coupon);
        entityManager.flush();
    }

    @Test
    void 발급_순번_중간에_구멍이_있으면_SEQUENCE_GAP을_탐지하고_DLQ_FAILED_건수를_메시지에_포함한다() {
        createIssueWithHistory(IssueStatus.ISSUED, "SEQ-1", "NONE", "ISSUED"); // sequenceNo=1
        createIssueWithHistory(IssueStatus.ISSUED, "SEQ-2", "NONE", "ISSUED"); // sequenceNo=2
        sequenceCounter++; // 3번은 Lua가 순번만 내주고 DB엔 끝내 안 들어온 상황을 흉내냄
        createIssueWithHistory(IssueStatus.ISSUED, "SEQ-4", "NONE", "ISSUED"); // sequenceNo=4

        insertIssueMessage(1L, "gap-dlq-1", "DLQ");
        insertIssueMessage(1L, "gap-failed-1", "FAILED");

        LocalDateTime asOfAt = LocalDateTime.now();
        Long maxSequenceNo = queries.findMaxSequenceNo(coupon.getCouponId(), asOfAt);
        assertThat(maxSequenceNo).isEqualTo(4L);

        List<VerificationDetail> details = queries.findSequenceGap(coupon.getCouponId(), maxSequenceNo, asOfAt);

        assertThat(details)
                .filteredOn(d -> d.getErrorType() == VerificationErrorType.SEQUENCE_GAP)
                .hasSize(1)
                .first()
                .satisfies(d -> {
                    assertThat(d.getMessage()).contains("DLQ 1건");
                    assertThat(d.getMessage()).contains("FAILED(재시도중) 1건");
                });
    }

    @Test
    void DLQ_메시지가_있으면_STOCK_NOT_RESTORED를_탐지한다() {
        CouponIssue issue = createIssueWithHistory(IssueStatus.ISSUED, "DLQ-1", "NONE", "ISSUED");
        insertIssueMessage(issue.getUser().getUserId(), "dlq-req-1", "DLQ");

        LocalDateTime asOfAt = LocalDateTime.now();
        List<VerificationDetail> details = queries.findStockNotRestored(coupon.getCouponId(), asOfAt);

        assertThat(details).anyMatch(d -> d.getErrorType() == VerificationErrorType.STOCK_NOT_RESTORED);
    }

    private void insertIssueMessage(Long userId, String requestId, String status) {
        messageSequenceCounter++;

        entityManager.createNativeQuery("""
                INSERT INTO issue_message
                    (coupon_id, user_id, sequence_no, message_key, topic, payload, status, retry_count, created_at)
                VALUES (:couponId, :userId, :sequenceNo, :requestId, 'coupon-issue-events', '{}', :status, 1, NOW(6))
                """)
                .setParameter("couponId", coupon.getCouponId())
                .setParameter("userId", userId)
                .setParameter("sequenceNo", messageSequenceCounter)
                .setParameter("requestId", requestId)
                .setParameter("status", status)
                .executeUpdate();
    }

    private CouponIssue createIssueWithHistory(IssueStatus status, String couponCode, String from, String to) {
        CouponIssue issue = createIssueWithoutHistory(status, couponCode);
        insertHistory(issue, from, to);
        return issue;
    }

    private CouponIssue createIssueWithoutHistory(IssueStatus status, String couponCode) {
        sequenceCounter++;

        AppUser user = AppUser.builder()
                .name("유저-" + couponCode).email(couponCode + "@test.com").phone("010-1111-1111")
                .role(UserRole.ROLE_MEMBER).build();
        entityManager.persist(user);

        CouponIssue issue = CouponIssue.builder()
                .coupon(coupon).user(user).sequenceNo(sequenceCounter)
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
