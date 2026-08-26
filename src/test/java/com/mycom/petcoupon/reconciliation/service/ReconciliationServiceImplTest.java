package com.mycom.petcoupon.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.service.CouponIssuePipelineDrainChecker;
import com.mycom.petcoupon.coupon.issue.service.PipelineDrainStatus;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.global.common.exception.GeneralException;
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
 *
 * StringRedisTemplate은 @DataJpaTest 슬라이스에 없는 빈이라 Mockito로 대체한다 — 이 클래스는
 * DB 정합성 로직만 검증 대상이라 실제 Redis는 필요 없다. Redis 비교 로직(STOCK_MISMATCH) 자체는
 * 별도 테스트에서 검증한다.
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

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private CouponIssuePipelineDrainChecker pipelineDrainChecker;

    private Coupon coupon;
    private int sequenceCounter = 0;

    @BeforeEach
    void setUp() {
        // 이 클래스의 테스트는 파이프라인 드레인 자체가 검증 대상이 아니라, 기본적으로 항상
        // "드레인 끝남"으로 두고 시작한다. 막히는 케이스는 별도 테스트에서 개별 스텁한다.
        when(pipelineDrainChecker.check(any())).thenReturn(new PipelineDrainStatus(0, 0, false));

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
        entityManager.flush();

        // reconcile()이 발급 마감(ENDED) 쿠폰만 받으므로, 빌더가 status를 못 여는 이 엔티티는
        // 네이티브 UPDATE로 직접 ENDED를 만든다. 진행 중 쿠폰 거부 테스트는 별도 쿠폰을 따로 만든다.
        // 네이티브 UPDATE는 영속성 컨텍스트를 안 거치므로, refresh() 안 하면 이후 coupon 객체가
        // (그리고 reconcile()의 findById가 1차 캐시에서 돌려주는 값도) 여전히 READY로 남는다.
        markCouponEnded(coupon.getCouponId());
        entityManager.refresh(coupon);

        // remainingQuantity는 CouponStock 생성자에서 totalQuantity와 같게 초기화됨(10) —
        // 이 클래스의 테스트는 재고를 안 건드리니 Redis도 항상 "10"을 반환하게 해서
        // STOCK_MISMATCH가 다른 검증(HISTORY_MISMATCH 등)의 MATCHED/errorCount 단언을 깨지 않게 한다.
        CouponStock stock = CouponStock.builder().coupon(coupon).totalQuantity(10).build();
        entityManager.persist(stock);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("10");

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
    void 상태별_건수를_실제로_집계한다() {
        createIssueWithHistory(IssueStatus.ISSUED, "ACTIVE-1", "NONE", "ISSUED");
        createIssueWithHistory(IssueStatus.ISSUED, "ACTIVE-2", "NONE", "ISSUED");
        createIssueWithHistory(IssueStatus.USED, "USED-1", "ISSUED", "USED");
        createIssueWithHistory(IssueStatus.EXPIRED, "EXPIRED-1", "ISSUED", "EXPIRED");

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getDbActiveCount()).isEqualTo(3L); // ISSUED 2건 + USED 1건
        assertThat(report.getDbExpiredCount()).isEqualTo(1L);
        assertThat(report.getDbDlqCount()).isZero(); // issue_message row를 안 만드니 DLQ 0건
        assertThat(report.getStockTotal()).isEqualTo(10);
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

    @Test
    void Redis_재고와_DB_재고가_다르면_STOCK_MISMATCH를_탐지한다() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("5"); // DB remaining은 10인데 Redis는 5

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getResult()).isEqualTo(ReconciliationResult.MISMATCHED);
        assertThat(report.getStockRemaining()).isEqualTo(10);
        assertThat(report.getRedisRemaining()).isEqualTo(5);
        assertThat(report.getVerificationDetails())
                .anyMatch(d -> d.getErrorType() == VerificationErrorType.STOCK_MISMATCH);
    }

    @Test
    void Redis_키가_없으면_STOCK_MISMATCH를_탐지한다() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getResult()).isEqualTo(ReconciliationResult.MISMATCHED);
        assertThat(report.getRedisRemaining()).isNull();
        assertThat(report.getVerificationDetails())
                .anyMatch(d -> d.getErrorType() == VerificationErrorType.STOCK_MISMATCH);
    }

    @Test
    void 발급_순번_중간에_구멍이_있으면_SEQUENCE_GAP을_탐지하고_DLQ_FAILED_건수를_메시지에_포함한다() {
        createIssueWithHistory(IssueStatus.ISSUED, "SEQ-1", "NONE", "ISSUED"); // sequenceNo=1
        createIssueWithHistory(IssueStatus.ISSUED, "SEQ-2", "NONE", "ISSUED"); // sequenceNo=2
        sequenceCounter++; // 3번은 Lua가 순번만 내주고 DB엔 끝내 안 들어온 상황을 흉내냄
        createIssueWithHistory(IssueStatus.ISSUED, "SEQ-4", "NONE", "ISSUED"); // sequenceNo=4

        // 위 갭(3번)의 원인을 설명해줄 issue_message를 실제로 하나씩 넣어서, countIssueMessagesByStatus의
        // IN(:statuses) 네이티브 바인딩이 빈 결과가 아니라 실제 매칭되는 값으로도 동작하는지 같이 검증
        insertIssueMessage(1L, "gap-dlq-1", "DLQ");
        insertIssueMessage(1L, "gap-failed-1", "FAILED");

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getMaxSequenceNo()).isEqualTo(4L);
        assertThat(report.getVerificationDetails())
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

        reconciliationService.reconcile(coupon.getCouponId());

        ReconciliationReport report = latestReport();
        assertThat(report.getDbDlqCount()).isEqualTo(1L);
        assertThat(report.getVerificationDetails())
                .anyMatch(d -> d.getErrorType() == VerificationErrorType.STOCK_NOT_RESTORED);
    }

    private int messageSequenceCounter = 900;

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

    @Test
    void 발급이_진행_중인_쿠폰은_정합성_검증을_거부한다() {
        Event event = Event.builder()
                .createdBy(coupon.getEvent().getCreatedBy()).name("진행중 이벤트").description("d")
                .openAt(LocalDateTime.now()).closeAt(LocalDateTime.now().plusDays(1)).build();
        entityManager.persist(event);

        Coupon activeCoupon = Coupon.builder()
                .event(event).name("진행중 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1000).minOrderAmount(1000)
                .issueStartAt(LocalDateTime.now()).issueEndAt(LocalDateTime.now().plusDays(1))
                .validDays(7).build();
        entityManager.persist(activeCoupon);
        entityManager.flush();

        assertThatThrownBy(() -> reconciliationService.reconcile(activeCoupon.getCouponId()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.RECONCILIATION_NOT_ALLOWED_YET);
    }

    @Test
    void 파이프라인이_드레인_안됐으면_ENDED여도_정합성_검증을_거부한다() {
        // 재사용되는 쿠폰은 2회차부터 status가 계속 ENDED라, ENDED 체크만으로는 "이번 회차가
        // 아직 진행 중인지"를 못 거른다 — 그래서 이 체크가 별도로 필요하다.
        when(pipelineDrainChecker.check(coupon.getCouponId()))
                .thenReturn(new PipelineDrainStatus(1, 0, false));

        assertThatThrownBy(() -> reconciliationService.reconcile(coupon.getCouponId()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.RECONCILIATION_PIPELINE_NOT_DRAINED);
    }

    @Test
    void 파이프라인_잔여_검사_자체가_실패하면_안전하게_정합성_검증을_거부한다() {
        when(pipelineDrainChecker.check(coupon.getCouponId()))
                .thenReturn(new PipelineDrainStatus(0, 0, true));

        assertThatThrownBy(() -> reconciliationService.reconcile(coupon.getCouponId()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.RECONCILIATION_PIPELINE_NOT_DRAINED);
    }

    private void markCouponEnded(Long couponId) {
        entityManager.createNativeQuery("UPDATE coupon SET status = 'ENDED' WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId)
                .executeUpdate();
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
