package com.mycom.petcoupon.reconciliation.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.reconciliation.repository.VerificationDetailRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "같은 파라미터로 재실행하면 된다"만으로는 부족하다 — 청크가 일부 커밋된 뒤 실패했을 때
 * 재시작이 processing을 처음부터 다시 하는지, 아니면 이어서 하는지를 실제로 확인해야 한다.
 * 이어서 하지 않으면 이미 커밋된 청크가 재처리되어 verification_detail이 중복 저장된다.
 *
 * chunk-size를 2로 줄여서 HISTORY_MISMATCH 5건을 [2,2,1] 3개 청크로 나눠 처리하게 만들고,
 * PoisonWriterConfig로 딱 한 번(두 번째 write() 호출, 즉 두 번째 청크)만 실패시킨다.
 *
 * 실행 전 MySQL/Redis가 떠 있어야 한다: docker compose up -d
 */
@SpringBootTest(properties = {
        "event.status.scheduler.enabled=false",
        "coupon.status.enabled=false",
        "reconciliation.batch.chunk-size=2",
        // StringRedisTemplate을 통째로 Mockito 목으로 대체하면, opsForStream()의 기본 리턴값(null)
        // 때문에 실 Redis Stream 컨테이너(CouponIssueStreamConfig)가 기동 시점에 NPE로 죽는다.
        // 컨텍스트 로딩은 @BeforeEach보다 먼저라 그때 가서 스텁해도 늦으므로, 아예 꺼서 우회한다.
        "coupon.issue.stream.enabled=false"
})
@Import(ReconciliationJobRestartTest.PoisonWriterConfig.class)
// PoisonWriterConfig의 callCount/alreadyFailed는 컨텍스트에 딱 한 번 만들어지는 빈 안의
// 상태라, 테스트 메서드가 두 개 이상이면 먼저 실행된 테스트가 "이미 한 번 실패시켰음"을
// 남겨서 다음 테스트의 poison writer가 더 이상 안 던진다 — 메서드마다 컨텍스트를 새로
// 띄워 이 상태를 초기화한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReconciliationJobRestartTest {

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private org.springframework.batch.core.job.Job reconciliationJob;

    @Autowired
    private ReconciliationReportRepository reconciliationReportRepository;

    @Autowired
    private VerificationDetailRepository verificationDetailRepository;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @MockitoBean
    private CouponIssuePipelineDrainChecker pipelineDrainChecker;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;
    private Coupon coupon;
    private int sequenceCounter = 0;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(platformTransactionManager);
        when(pipelineDrainChecker.check(any())).thenReturn(new PipelineDrainStatus(0, 0, 0, false));

        // 이 테스트는 historyMismatchStep의 재시작(체크포인트 이어받기)에만 집중한다 — Redis에
        // 이 테스트 쿠폰 재고 키가 실제로 없어서 STOCK_MISMATCH가 섞여 들어오면(별도 경로로
        // 저장돼 청크 카운트와 무관하게 잡힘) 건수 검증이 흐려진다. DB 재고(10)와 일치시켜 끈다.
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("10");

        transactionTemplate.executeWithoutResult(status -> setUpData());
    }

    private void setUpData() {
        AppUser admin = AppUser.builder()
                .name("재시작테스트 관리자").email("job-restart@test.com").phone("010-0000-0000")
                .role(UserRole.ROLE_ADMIN).build();
        entityManager.persist(admin);

        Event event = Event.builder()
                .createdBy(admin).name("재시작테스트 이벤트").description("d")
                .openAt(LocalDateTime.now()).closeAt(LocalDateTime.now().plusDays(1)).build();
        entityManager.persist(event);

        coupon = Coupon.builder()
                .event(event).name("재시작테스트 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
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

        // HISTORY_MISMATCH 5건 — chunk-size 2로 [청크1: 2건, 청크2: 2건, 청크3: 1건]
        for (int i = 1; i <= 5; i++) {
            createMismatchedIssue("RESTART-" + i);
        }
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
        // coupon_issue가 app_user를 참조하니, user_id를 먼저 모아두고 coupon_issue부터 지운다
        // (반대 순서로 하면 FK 위반).
        @SuppressWarnings("unchecked")
        List<Number> issueUserIds = entityManager.createNativeQuery(
                "SELECT user_id FROM coupon_issue WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId).getResultList();

        entityManager.createNativeQuery("DELETE FROM coupon_issue WHERE coupon_id = :couponId")
                .setParameter("couponId", couponId).executeUpdate();
        for (Number userId : issueUserIds) {
            entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id = :userId")
                    .setParameter("userId", userId.longValue()).executeUpdate();
        }
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
    }

    @Test
    void 청크가_일부_커밋된_뒤_실패하면_재시작시_이어서_처리하고_중복_저장하지_않는다() throws Exception {
        var jobParameters = new JobParametersBuilder()
                .addLong("couponId", coupon.getCouponId())
                .addLocalDateTime("asOfAt", LocalDateTime.now())
                .toJobParameters();

        // 1차 실행 — 두 번째 청크(historyMismatchStep)에서 의도적으로 실패
        JobExecution firstExecution = jobOperator.start(reconciliationJob, jobParameters);
        assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.FAILED);

        entityManager.clear();
        ReconciliationReport report = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .findFirst()
                .orElseThrow();

        // 실패 시점에 첫 번째 청크(2건)는 이미 커밋되어 남아있어야 한다 — 롤백되지 않음.
        long countAfterFailure = verificationDetailRepository.countByReport_ReportId(report.getReportId());
        assertThat(countAfterFailure).isEqualTo(2L);

        // 재시작 — 같은 JobParameters로 다시 실행(같은 JobInstance를 잇는다).
        JobExecution secondExecution = jobOperator.start(reconciliationJob, jobParameters);
        assertThat(secondExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(secondExecution.getJobInstanceId()).isEqualTo(firstExecution.getJobInstanceId());

        entityManager.clear();

        // reconciliation_report가 새로 또 생기지 않고 하나만 있어야 한다(같은 reportId 재사용).
        long reportCount = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .count();
        assertThat(reportCount).isEqualTo(1L);

        // 5건 전부, 중복 없이 정확히 5건만 있어야 한다.
        long finalCount = verificationDetailRepository.countByReport_ReportId(report.getReportId());
        assertThat(finalCount).isEqualTo(5L);

        long distinctIssueCount = verificationDetailRepository.findAll().stream()
                .filter(d -> d.getReport().getReportId().equals(report.getReportId()))
                .map(VerificationDetail::getCouponIssueId)
                .distinct()
                .count();
        assertThat(distinctIssueCount).isEqualTo(5L);

        entityManager.clear();
        ReconciliationReport finalReport = reconciliationReportRepository.findById(report.getReportId()).orElseThrow();
        assertThat(finalReport.getErrorCount()).isEqualTo(5L);
    }

    // preconditionCheckStep에 allowStartIfComplete(true)를 걸지 않으면, 1차 실행에서 이미
    // COMPLETED된 이 Step은 재시작 시 Spring Batch가 그대로 건너뛴다 — 그 사이 파이프라인이
    // 다시 오염돼도(DLQ 재처리·늦은 Kafka 메시지 등) 재시작이 이를 못 잡고 체크포인트부터
    // 계속 진행해버린다. 이 테스트는 재시작 직전에 드레인 상태를 다시 "막힘"으로 바꿔서,
    // 재시작이 이어서 처리하지 않고 사전조건에서 다시 거절되는지 확인한다.
    @Test
    void 재시작_전에_파이프라인이_다시_막히면_사전조건에서_다시_거절한다() throws Exception {
        var jobParameters = new JobParametersBuilder()
                .addLong("couponId", coupon.getCouponId())
                .addLocalDateTime("asOfAt", LocalDateTime.now())
                .toJobParameters();

        // 1차 실행 — 두 번째 청크(historyMismatchStep)에서 의도적으로 실패(기존 테스트와 동일).
        JobExecution firstExecution = jobOperator.start(reconciliationJob, jobParameters);
        assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.FAILED);

        entityManager.clear();
        ReconciliationReport report = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(coupon.getCouponId()))
                .findFirst()
                .orElseThrow();

        long countAfterFirstFailure = verificationDetailRepository.countByReport_ReportId(report.getReportId());
        assertThat(countAfterFirstFailure).isEqualTo(2L);

        // 재시작 전, 파이프라인이 다시 막혔다고 가정한다 — 예: DLQ 재처리로 Outbox에 미확정
        // 메시지가 다시 생김.
        when(pipelineDrainChecker.check(any())).thenReturn(new PipelineDrainStatus(1, 0, 0, false));

        // 재시작 — preconditionCheckStep이 건너뛰어지지 않고 다시 돌아서, 여기서 즉시 거절돼야 한다.
        JobExecution secondExecution = jobOperator.start(reconciliationJob, jobParameters);
        assertThat(secondExecution.getStatus()).isEqualTo(BatchStatus.FAILED);

        List<Throwable> failures = secondExecution.getAllFailureExceptions();
        assertThat(failures).anyMatch(failure ->
                failure instanceof GeneralException generalException
                        && generalException.getErrorCode() == CouponErrorCode.RECONCILIATION_PIPELINE_NOT_DRAINED
        );

        // 사전조건에서 막혔으므로 뒤쪽 Step(체크포인트 이어받기)은 전혀 진행되지 않아야 한다 —
        // verification_detail 건수가 1차 실패 시점(2건)에서 그대로여야 한다.
        entityManager.clear();
        long countAfterSecondAttempt = verificationDetailRepository.countByReport_ReportId(report.getReportId());
        assertThat(countAfterSecondAttempt).isEqualTo(2L);
    }

    private void createMismatchedIssue(String couponCode) {
        sequenceCounter++;

        AppUser user = AppUser.builder()
                .name("유저-" + couponCode).email(couponCode + "@test.com").phone("010-1111-1111")
                .role(UserRole.ROLE_MEMBER).build();
        entityManager.persist(user);

        // status는 USED인데 이력은 ISSUED까지만 남겨서 HISTORY_MISMATCH를 만든다.
        CouponIssue issue = CouponIssue.builder()
                .coupon(coupon).user(user).sequenceNo(sequenceCounter)
                .couponCode(couponCode).requestId("req-" + couponCode)
                .status(IssueStatus.USED)
                .usedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        entityManager.persist(issue);
        entityManager.flush();

        entityManager.createNativeQuery("""
                INSERT INTO coupon_issue_history
                    (coupon_issue_id, coupon_id, user_id, from_status, to_status, actor_type, created_at)
                VALUES (:issueId, :couponId, :userId, 'NONE', 'ISSUED', 'SYSTEM', NOW(6))
                """)
                .setParameter("issueId", issue.getCouponIssueId())
                .setParameter("couponId", coupon.getCouponId())
                .setParameter("userId", user.getUserId())
                .executeUpdate();
    }

    @TestConfiguration
    static class PoisonWriterConfig {

        @Bean
        @Primary
        ItemWriter<VerificationDetail> poisonVerificationDetailItemWriter(VerificationDetailRepository repository) {
            AtomicInteger callCount = new AtomicInteger(0);
            AtomicBoolean alreadyFailed = new AtomicBoolean(false);

            return (Chunk<? extends VerificationDetail> chunk) -> {
                int call = callCount.incrementAndGet();
                if (call == 2 && alreadyFailed.compareAndSet(false, true)) {
                    throw new RuntimeException("의도적 실패 — 재시작(체크포인트 이어받기) 테스트용");
                }
                repository.saveAll(chunk.getItems());
            };
        }
    }
}
