package com.mycom.petcoupon.reconciliation.batch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.reconciliation.repository.VerificationDetailRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Job이 Step0~5를 전부 거쳐 COMPLETED로 끝나는지, 그리고 청크 Reader(historyMismatchStep/
 * invalidTransitionStep)가 실제로 페이징 쿼리로 HISTORY_MISMATCH/INVALID_STATUS를 찾아
 * VerificationDetail로 저장하는지 확인한다. 대용량(300만 건) 페이징 자체의 성능/경계 검증은
 * 별도 단계(더미데이터 확보 후)에서 한다 — 여기서는 소량 데이터로 로직 정확성만 본다.
 *
 * 실행 전 MySQL/Redis가 떠 있어야 한다: docker compose up -d
 *
 * coupon.issue.stream.key/group을 이 테스트 전용 값으로 오버라이드한다 — 앱 기본 키를 그대로
 * 쓰면 다른 테스트(또는 이전 실행, 심지어 진짜 앱 인스턴스)가 그 키에 남긴 pending 때문에
 * preconditionCheckStep이 드레인 안 됐다고 오판해 Job이 실패한다. enabled=false는 이 앱이
 * Stream Consumer를 새로 만드는 것만 막을 뿐 드레인 체크 자체(raw Redis 조회)는 막지 못해서
 * 근본 해결이 안 된다 — pending을 idle 시간과 무관하게 무조건 막도록 바뀐 뒤로 이 오염에
 * 특히 취약해졌다. CouponIssuePipelineDrainCheckerImplTest와 같은 방식으로 키 자체를 격리한다.
 */
@SpringBootTest(properties = {
        "event.status.scheduler.enabled=false",
        "coupon.status.enabled=false",
        "coupon.issue.stream.key=coupon:issue:stream:job-config-test",
        "coupon.issue.stream.group=job-config-test-group"
})
@SpringBatchTest
class ReconciliationJobConfigTest {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private ReconciliationReportRepository reconciliationReportRepository;

    @Autowired
    private VerificationDetailRepository verificationDetailRepository;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;
    private Coupon coupon;
    private int sequenceCounter = 0;
    private final List<Long> extraUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(platformTransactionManager);
        transactionTemplate.executeWithoutResult(status -> setUpData());
    }

    private void setUpData() {
        AppUser admin = AppUser.builder()
                .name("배치골격 관리자").email("batch-skeleton@test.com").phone("010-0000-0000")
                .role(UserRole.ROLE_ADMIN).build();
        entityManager.persist(admin);

        Event event = Event.builder()
                .createdBy(admin).name("배치골격 이벤트").description("d")
                .openAt(LocalDateTime.now()).closeAt(LocalDateTime.now().plusDays(1)).build();
        entityManager.persist(event);

        coupon = Coupon.builder()
                .event(event).name("배치골격 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1000).minOrderAmount(1000)
                .issueStartAt(LocalDateTime.now()).issueEndAt(LocalDateTime.now().plusDays(1))
                .validDays(7).build();
        entityManager.persist(coupon);
        entityManager.flush();

        entityManager.createNativeQuery("UPDATE coupon SET status = 'ENDED' WHERE coupon_id = :couponId")
                .setParameter("couponId", coupon.getCouponId())
                .executeUpdate();
        entityManager.refresh(coupon);

        CouponStock stock = CouponStock.builder().coupon(coupon).totalQuantity(10).build();
        entityManager.persist(stock);
        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> tearDownData());
    }

    private void tearDownData() {
        Long couponId = coupon.getCouponId();
        Long eventId = coupon.getEvent().getEventId();
        Long adminId = coupon.getEvent().getCreatedBy().getUserId();

        entityManager.createNativeQuery(
                "DELETE FROM verification_detail WHERE report_id IN (SELECT report_id FROM reconciliation_report WHERE coupon_id = :couponId)")
                .setParameter("couponId", couponId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM reconciliation_report WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM coupon_issue_history WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM issue_message WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM coupon_issue WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM coupon_stock WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM coupon WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId).executeUpdate();
        // 전체 테스트를 같이 돌리면 다른 테스트 컨텍스트의 이벤트 상태 스케줄러가 백그라운드에서
        // 계속 돌면서 이 이벤트에 event_status_history를 남길 수 있다 — event보다 먼저 지운다.
        entityManager.createNativeQuery("DELETE FROM event_status_history WHERE event_id = :eventId")
                .setParameter("eventId", eventId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM event WHERE event_id = :eventId")
                .setParameter("eventId", eventId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id = :userId")
                .setParameter("userId", adminId).executeUpdate();
        for (Long userId : extraUserIds) {
            entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id = :userId")
                    .setParameter("userId", userId).executeUpdate();
        }
    }

    @Test
    void Job이_전체_Step을_거쳐_COMPLETED로_끝나고_리포트를_남긴다() throws Exception {
        var jobParameters = new JobParametersBuilder()
                .addLong("couponId", coupon.getCouponId())
                .addLocalDateTime("asOfAt", LocalDateTime.now())
                .toJobParameters();

        JobExecution jobExecution = jobOperatorTestUtils.startJob(jobParameters);

        assertThat(jobExecution.getStatus().toString()).isEqualTo("COMPLETED");

        entityManager.clear();
        ReconciliationReport report = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .findFirst()
                .orElseThrow();

        // 이 쿠폰은 Redis 재고 키를 만든 적이 없어서(Lua 초기화 미실행) readRedisStock()이 null을
        // 반환하고, 이는 실제로 STOCK_MISMATCH로 잡혀야 정상이다(ReconciliationServiceImplTest의
        // "Redis_키가_없으면_STOCK_MISMATCH를_탐지한다"와 동일한 케이스) — 골격이 RemainingChecksTasklet을
        // 통해 이 로직을 제대로 재사용하고 있다는 뜻이라 MISMATCHED가 맞다.
        // STOCK_MISMATCH는 couponIssueId가 없는 쿠폰 전체 단위 문제라 errorCount(발급 건 단위)에는 안 잡힌다.
        assertThat(report.getResult()).isEqualTo(ReconciliationResult.MISMATCHED);
        assertThat(report.getTotalCount()).isZero();
        assertThat(report.getSuccessCount()).isZero();
        assertThat(report.getErrorCount()).isZero();
        assertThat(report.getFinishedAt()).isNotNull();
        assertThat(report.getStockTotal()).isEqualTo(10);
        assertThat(report.getRedisRemaining()).isNull();
    }

    @Test
    void 같은_couponId와_asOfAt으로_다시_실행하면_거부된다() throws Exception {
        var jobParameters = new JobParametersBuilder()
                .addLong("couponId", coupon.getCouponId())
                .addLocalDateTime("asOfAt", LocalDateTime.now())
                .toJobParameters();

        JobExecution first = jobOperatorTestUtils.startJob(jobParameters);
        assertThat(first.getStatus().toString()).isEqualTo("COMPLETED");

        // Spring Batch는 JobParameters가 같으면 같은 JobInstance로 취급한다 — asOfAt을
        // run.id 같은 별도 유일성 파라미터 없이 JobParameters에 그대로 쓴 이유가 이거다.
        // 이미 COMPLETED된 조합을 그대로 다시 실행하면 재시작이 아니라 "이미 끝난 실행"으로
        // 보고 거부한다 — 별도 코드 없이 중복 실행 방지가 되는 걸 확인한다.
        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(jobParameters))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void 청크_Reader가_HISTORY_MISMATCH와_INVALID_STATUS를_실제로_찾아_저장한다() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            // status는 USED인데 이력은 ISSUED까지만 남아 HISTORY_MISMATCH
            createIssueWithHistory(IssueStatus.USED, "JOB-MISMATCH-1", "NONE", "ISSUED");
            // 화이트리스트에 없는 전이(EXPIRED -> USED)를 추가해 INVALID_STATUS
            CouponIssue invalidIssue = createIssueWithHistory(IssueStatus.EXPIRED, "JOB-INVALID-1", "NONE", "ISSUED");
            insertHistory(invalidIssue, "EXPIRED", "USED");
        });

        var jobParameters = new JobParametersBuilder()
                .addLong("couponId", coupon.getCouponId())
                .addLocalDateTime("asOfAt", LocalDateTime.now())
                .toJobParameters();

        JobExecution jobExecution = jobOperatorTestUtils.startJob(jobParameters);
        assertThat(jobExecution.getStatus().toString()).isEqualTo("COMPLETED");

        entityManager.clear();
        ReconciliationReport report = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .findFirst()
                .orElseThrow();

        List<VerificationDetail> details = verificationDetailRepository.findAll().stream()
                .filter(d -> d.getReport().getReportId().equals(report.getReportId()))
                .toList();

        assertThat(report.getResult()).isEqualTo(ReconciliationResult.MISMATCHED);
        assertThat(report.getTotalCount()).isEqualTo(2L);
        assertThat(details).anyMatch(d -> d.getErrorType() == VerificationErrorType.HISTORY_MISMATCH);
        assertThat(details).anyMatch(d -> d.getErrorType() == VerificationErrorType.INVALID_STATUS);

        // 같은 발급 건(JOB-INVALID-1)이 HISTORY_MISMATCH(최종 이력은 USED인데 status는 EXPIRED)와
        // INVALID_STATUS 둘 다에 걸려도 errorCount는 발급 건 단위로 하나만 센다 — 서로 다른
        // 청크 Step(historyMismatchStep/invalidTransitionStep)이 각각 써도 이 집계가 깨지지 않는지 확인한다.
        assertThat(report.getErrorCount()).isEqualTo(2L);
        assertThat(report.getTotalCount()).isEqualTo(report.getSuccessCount() + report.getErrorCount());
    }

    // historyMismatchReader가 이력에도 asOfAt 제한을 걸던 예전 버전에서는, asOfAt 시점엔
    // ISSUED였다가 asOfAt 이후 정상적으로 USED로 바뀐 건을 "이력=ISSUED, 현재상태=USED가
    // 다르다"며 HISTORY_MISMATCH로 오탐했다. 발급기간 종료(coupon.status=ENDED, 사전조건이
    // 요구하는 전부) 후에도 사용은 계속 일어날 수 있어 실제로 흔히 생기는 상황이다.
    @Test
    void asOfAt_이후_정상적으로_상태가_바뀐_건은_HISTORY_MISMATCH로_오탐하지_않는다() throws Exception {
        CouponIssue issue = transactionTemplate.execute(status ->
                createIssueWithoutHistory(IssueStatus.USED, "AFTER-ASOFAT-USED"));

        // 이 시점의 issue.created_at은 이미 DB에 커밋돼 있어 asOfAt보다 앞선다.
        LocalDateTime asOfAt = LocalDateTime.now();

        transactionTemplate.executeWithoutResult(status -> {
            // asOfAt 이전: ISSUED로 발급.
            insertHistoryAt(issue, "NONE", "ISSUED", asOfAt.minusSeconds(10));
            // asOfAt 이후: 정상적으로 사용 처리 — 배치가 실제로 도는 시점엔 이미 반영돼 있다.
            insertHistoryAt(issue, "ISSUED", "USED", asOfAt.plusSeconds(10));
        });

        var jobParameters = new JobParametersBuilder()
                .addLong("couponId", coupon.getCouponId())
                .addLocalDateTime("asOfAt", asOfAt)
                .toJobParameters();

        JobExecution jobExecution = jobOperatorTestUtils.startJob(jobParameters);
        assertThat(jobExecution.getStatus().toString()).isEqualTo("COMPLETED");

        entityManager.clear();
        ReconciliationReport report = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .findFirst()
                .orElseThrow();

        List<VerificationDetail> details = verificationDetailRepository.findAll().stream()
                .filter(d -> d.getReport().getReportId().equals(report.getReportId()))
                .toList();

        // report.getResult()는 다른 테스트와 같은 이유(Redis 재고 키 미생성 → STOCK_MISMATCH)로
        // 여전히 MISMATCHED다 — 그건 이 발급 건과 무관한 쿠폰 전체 단위 문제라 여기서는 안 본다.
        // 이 테스트가 보는 건 이 발급 건이 HISTORY_MISMATCH로(발급 건 단위 오탐) 안 잡히는 것이다.
        assertThat(details).noneMatch(d -> d.getErrorType() == VerificationErrorType.HISTORY_MISMATCH);
        assertThat(report.getErrorCount()).isZero();
    }

    @Test
    void 실제_Job_실행으로_SEQUENCE_GAP과_STOCK_NOT_RESTORED를_찾아_저장한다() throws Exception {
        // SEQUENCE_GAP은 RemainingChecksTasklet(소량, assignReport()), STOCK_NOT_RESTORED는
        // stockNotRestoredStep(청크, entityManager.getReference())로 서로 다른 경로를 거치지만
        // 최종적으로 같은 report에 쌓이는지를 실제 Job 실행으로 확인한다 — STOCK_NOT_RESTORED는
        // 원래 RemainingChecksTasklet 안에서 findStockNotRestored()(getResultList()로 전체 로드)로
        // 처리했으나, 대량 적체 시 OOM 위험 때문에 청크 Step으로 옮겼다(ReconciliationJobConfig
        // 클래스 Javadoc 참고). 이 테스트는 그 이관 후에도 결과가 report에 정상 저장됨을 검증한다.
        //
        // DLQ 1건 + ABANDONED 1건을 같이 둔다(#149) — DLQ는 아직 관리자 결정을 기다리는 정상
        // 상태라 STOCK_NOT_RESTORED 대상이 아니고(dbDlqCount 집계 대상일 뿐), ABANDONED만
        // "재처리를 포기했지만 재고가 안 돌아온" 대상이다.
        transactionTemplate.executeWithoutResult(status -> {
            createIssueWithHistory(IssueStatus.ISSUED, "SEQ-1", "NONE", "ISSUED"); // sequenceNo=1
            createIssueWithHistory(IssueStatus.ISSUED, "SEQ-2", "NONE", "ISSUED"); // sequenceNo=2
            sequenceCounter++; // 3번은 Lua가 순번만 내주고 DB엔 끝내 안 들어온 상황을 흉내냄
            createIssueWithHistory(IssueStatus.ISSUED, "SEQ-4", "NONE", "ISSUED"); // sequenceNo=4

            insertIssueMessage(1L, "job-gap-dlq-1", "DLQ");
            insertIssueMessage(1L, "job-gap-abandoned-1", "ABANDONED");
        });

        var jobParameters = new JobParametersBuilder()
                .addLong("couponId", coupon.getCouponId())
                .addLocalDateTime("asOfAt", LocalDateTime.now())
                .toJobParameters();

        JobExecution jobExecution = jobOperatorTestUtils.startJob(jobParameters);
        assertThat(jobExecution.getStatus().toString()).isEqualTo("COMPLETED");

        entityManager.clear();
        ReconciliationReport report = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .findFirst()
                .orElseThrow();

        List<VerificationDetail> details = verificationDetailRepository.findAll().stream()
                .filter(d -> d.getReport().getReportId().equals(report.getReportId()))
                .toList();

        assertThat(report.getResult()).isEqualTo(ReconciliationResult.MISMATCHED);
        assertThat(report.getMaxSequenceNo()).isEqualTo(4L);
        assertThat(report.getDbDlqCount()).isEqualTo(1L);
        assertThat(details).anyMatch(d -> d.getErrorType() == VerificationErrorType.SEQUENCE_GAP);
        assertThat(details).anyMatch(d -> d.getErrorType() == VerificationErrorType.STOCK_NOT_RESTORED);
    }

    // #149 회귀 테스트 — status='ABANDONED'만 보면 정상적으로 복구된 건까지 전부 오탐한다.
    // stock_restored_at이 채워진 ABANDONED 건은 STOCK_NOT_RESTORED 대상이 아니어야 한다.
    @Test
    void 재고_복구가_확인된_ABANDONED_건은_STOCK_NOT_RESTORED로_잡지_않는다() throws Exception {
        transactionTemplate.executeWithoutResult(status ->
                insertIssueMessage(1L, "restored-abandon-1", "ABANDONED", true));

        var jobParameters = new JobParametersBuilder()
                .addLong("couponId", coupon.getCouponId())
                .addLocalDateTime("asOfAt", LocalDateTime.now())
                .toJobParameters();

        JobExecution jobExecution = jobOperatorTestUtils.startJob(jobParameters);
        assertThat(jobExecution.getStatus().toString()).isEqualTo("COMPLETED");

        entityManager.clear();
        ReconciliationReport report = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .findFirst()
                .orElseThrow();

        List<VerificationDetail> details = verificationDetailRepository.findAll().stream()
                .filter(d -> d.getReport().getReportId().equals(report.getReportId()))
                .toList();

        assertThat(details).noneMatch(d -> d.getErrorType() == VerificationErrorType.STOCK_NOT_RESTORED);
    }

    private int messageSequenceCounter = 900;

    private void insertIssueMessage(Long userId, String requestId, String status) {
        insertIssueMessage(userId, requestId, status, false);
    }

    // stockRestored=true면 abandon()이 복구를 확인한 뒤 채우는 stock_restored_at까지 같이 넣는다
    // (#149) — status='ABANDONED'만으로는 복구 여부를 구분 못 해서, 이 컬럼이 있는 ABANDONED 건은
    // STOCK_NOT_RESTORED 대상이 아니어야 함을 증명하는 테스트용.
    private void insertIssueMessage(Long userId, String requestId, String status, boolean stockRestored) {
        messageSequenceCounter++;

        entityManager.createNativeQuery("""
                INSERT INTO issue_message
                    (coupon_id, user_id, sequence_no, message_key, topic, payload, status, retry_count,
                     created_at, stock_restored_at)
                VALUES (:couponId, :userId, :sequenceNo, :requestId, 'coupon-issue-events', '{}', :status, 1,
                        NOW(6), :stockRestoredAt)
                """)
                .setParameter("couponId", coupon.getCouponId())
                .setParameter("userId", userId)
                .setParameter("sequenceNo", messageSequenceCounter)
                .setParameter("requestId", requestId)
                .setParameter("status", status)
                .setParameter("stockRestoredAt", stockRestored ? LocalDateTime.now() : null)
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
        entityManager.flush();
        extraUserIds.add(user.getUserId());

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

    private void insertHistoryAt(CouponIssue issue, String from, String to, LocalDateTime createdAt) {
        entityManager.createNativeQuery("""
                INSERT INTO coupon_issue_history
                    (coupon_issue_id, coupon_id, user_id, from_status, to_status, actor_type, created_at)
                VALUES (:issueId, :couponId, :userId, :from, :to, 'SYSTEM', :createdAt)
                """)
                .setParameter("issueId", issue.getCouponIssueId())
                .setParameter("couponId", coupon.getCouponId())
                .setParameter("userId", issue.getUser().getUserId())
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("createdAt", createdAt)
                .executeUpdate();
    }
}
