package com.mycom.petcoupon.reconciliation.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * AdminReconciliationController가 이 서비스를 쓰도록 바뀌면서, "실패 응답이 예전
 * ReconciliationServiceImpl.reconcile()과 정확히 같은 GeneralException으로 나오는지"가
 * 컨트롤러 계약(프론트에 이미 전달된 API)을 안 깨는 핵심이다 — 여기서 실제 Job을 돌려 확인한다.
 *
 * 실행 전 MySQL/Redis가 떠 있어야 한다: docker compose up -d
 *
 * coupon.issue.stream.key/group을 이 테스트 전용 값으로 오버라이드한다 — 앱 기본 키를 그대로
 * 쓰면 다른 테스트가 그 키에 남긴 pending 때문에 preconditionCheckStep이 드레인 안 됐다고
 * 오판해 Job이 실패한다. enabled=false는 이 앱이 Stream Consumer를 새로 만드는 것만 막을 뿐
 * 드레인 체크 자체(raw Redis 조회)는 막지 못해서 근본 해결이 안 된다 — pending을 idle 시간과
 * 무관하게 무조건 막도록 바뀐 뒤로 이 오염에 특히 취약해졌다.
 */
@SpringBootTest(properties = {
        "event.status.scheduler.enabled=false",
        "coupon.status.enabled=false",
        "coupon.issue.stream.key=coupon:issue:stream:trigger-svc-test",
        "coupon.issue.stream.group=trigger-svc-test-group"
})
// PoisonWriterConfig는 항상 실패하는 청크 Writer라 이 클래스의 다른 테스트에도 적용되지만,
// 다른 테스트의 쿠폰(endedCoupon/activeCoupon)에는 HISTORY_MISMATCH/INVALID_STATUS/
// STOCK_NOT_RESTORED 대상 데이터가 없어 청크 Step의 write()가 애초에 호출되지 않는다 — 실패
// 시나리오를 재현하는 아래 테스트에서만 실제로 영향을 준다.
@Import(ReconciliationJobTriggerServiceTest.PoisonWriterConfig.class)
class ReconciliationJobTriggerServiceTest {

    @Autowired
    private ReconciliationJobTriggerService reconciliationJobTriggerService;

    @Autowired
    private ReconciliationReportRepository reconciliationReportRepository;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;
    private Coupon endedCoupon;
    private Coupon activeCoupon;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(platformTransactionManager);
        transactionTemplate.executeWithoutResult(status -> setUpData());
    }

    private void setUpData() {
        AppUser admin = AppUser.builder()
                .name("트리거서비스 관리자").email("job-trigger@test.com").phone("010-0000-0000")
                .role(UserRole.ROLE_ADMIN).build();
        entityManager.persist(admin);

        Event event = Event.builder()
                .createdBy(admin).name("트리거서비스 이벤트").description("d")
                .openAt(LocalDateTime.now()).closeAt(LocalDateTime.now().plusDays(1)).build();
        entityManager.persist(event);

        endedCoupon = Coupon.builder()
                .event(event).name("트리거서비스 ENDED 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1000).minOrderAmount(1000)
                .issueStartAt(LocalDateTime.now()).issueEndAt(LocalDateTime.now().plusDays(1))
                .validDays(7).build();
        entityManager.persist(endedCoupon);

        activeCoupon = Coupon.builder()
                .event(event).name("트리거서비스 진행중 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1000).minOrderAmount(1000)
                .issueStartAt(LocalDateTime.now()).issueEndAt(LocalDateTime.now().plusDays(1))
                .validDays(7).build();
        entityManager.persist(activeCoupon);
        entityManager.flush();

        entityManager.createNativeQuery("UPDATE coupon SET status = 'ENDED' WHERE coupon_id = :couponId")
                .setParameter("couponId", endedCoupon.getCouponId())
                .executeUpdate();
        entityManager.refresh(endedCoupon);

        CouponStock stock = CouponStock.builder().coupon(endedCoupon).totalQuantity(10).build();
        entityManager.persist(stock);
        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> tearDownData());
    }

    private void tearDownData() {
        Long eventId = endedCoupon.getEvent().getEventId();
        Long adminId = endedCoupon.getEvent().getCreatedBy().getUserId();

        for (Long couponId : new Long[]{endedCoupon.getCouponId(), activeCoupon.getCouponId()}) {
            entityManager.createNativeQuery(
                    "DELETE FROM verification_detail WHERE report_id IN (SELECT report_id FROM reconciliation_report WHERE coupon_id = :couponId)")
                    .setParameter("couponId", couponId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM reconciliation_report WHERE coupon_id = :couponId")
                    .setParameter("couponId", couponId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM issue_message WHERE coupon_id = :couponId")
                    .setParameter("couponId", couponId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM coupon_stock WHERE coupon_id = :couponId")
                    .setParameter("couponId", couponId).executeUpdate();

            // Job이_청크_Step에서_실패하면... 테스트가 endedCoupon에 coupon_issue를 만든다 —
            // FK 순서상 coupon보다 먼저 지워야 한다(coupon_issue_history -> coupon_issue ->
            // 참조하던 app_user 순).
            entityManager.createNativeQuery("DELETE FROM coupon_issue_history WHERE coupon_id = :couponId")
                    .setParameter("couponId", couponId).executeUpdate();
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

            entityManager.createNativeQuery("DELETE FROM coupon WHERE coupon_id = :couponId")
                    .setParameter("couponId", couponId).executeUpdate();
        }
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
    void 정상_쿠폰이면_Job을_끝까지_돌려_리포트를_돌려준다() {
        ReconciliationBatchResult result = reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());

        // Controller(ReconciliationConverter)가 이 시점엔 이미 끝난 트랜잭션 밖에서 report를 읽는다 —
        // coupon은 여전히 지연 프록시지만 식별자(getCouponId)만 읽는 건 세션 없이도 안전하다.
        assertThat(result.report().getCoupon().getCouponId()).isEqualTo(endedCoupon.getCouponId());
        assertThat(result.report().getStockTotal()).isEqualTo(10);

        // verification_detail은 이제 report.getVerificationDetails()(지연로딩 컬렉션)로 읽지 않고,
        // VerificationDetailRepository의 전용 쿼리로 이미 다 가져와 있다 — 그래서 트랜잭션 밖에서
        // LazyInitializationException 없이 바로 읽을 수 있어야 한다(실제 E2E 호출로 원래 재현했던
        // 문제와 같은 종류다).
        assertThat(result.verificationDetailCount()).isEqualTo(1L);
        assertThat(result.topVerificationDetails())
                .anyMatch(d -> d.getErrorType() == VerificationErrorType.STOCK_MISMATCH);
        assertThat(result.verificationDetailCountByType())
                .containsEntry(VerificationErrorType.STOCK_MISMATCH, 1L);
    }

    // reportInitStep이 report row(임시값)를 커밋한 뒤, 청크 Step(historyMismatchStep)에서
    // Job이 실패하면 그 row가 finishedAt=null, result=MATCHED로 영원히 남아 "검증했고
    // 문제없음"으로 오인되는 문제를 고쳤다 — result는 ERROR로 초기화하고, 실패가 확정되면
    // finishedAt도 채운다. PoisonWriterConfig로 historyMismatchStep의 write()를 항상 실패시켜
    // 실제로 그렇게 되는지 확인한다.
    @Test
    void Job이_청크_Step에서_실패하면_리포트가_ERROR로_남고_finishedAt이_채워진다() {
        transactionTemplate.executeWithoutResult(status -> createMismatchedIssue(endedCoupon, "TRIGGER-FAIL-1"));

        assertThatThrownBy(() -> reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId()))
                .isInstanceOf(GeneralException.class);

        entityManager.clear();
        ReconciliationReport report = reconciliationReportRepository.findAll().stream()
                .filter(r -> r.getCoupon().getCouponId().equals(endedCoupon.getCouponId()))
                .findFirst()
                .orElseThrow();

        assertThat(report.getResult()).isEqualTo(ReconciliationResult.ERROR);
        assertThat(report.getFinishedAt()).isNotNull();
    }

    // #202 — 재고를 다 쓴 쿠폰은 발급 기간이 남아 있어도 더 발급될 수 없으므로 검증 대상이다.
    // 예전에는 ENDED만 통과해서, 발급 기간이 한 달 남은 쿠폰은 품절돼도 그때까지 검증할 수 없었다.
    @Test
    void 품절된_쿠폰도_Job을_끝까지_돌려_리포트를_돌려준다() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE coupon SET status = 'SOLD_OUT' WHERE coupon_id = :couponId")
                        .setParameter("couponId", endedCoupon.getCouponId())
                        .executeUpdate());

        ReconciliationBatchResult result = reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());

        assertThat(result.report().getCoupon().getCouponId()).isEqualTo(endedCoupon.getCouponId());
        assertThat(result.report().getFinishedAt()).isNotNull();
    }

    @Test
    void 발급이_진행중인_쿠폰이면_예전과_같은_예외로_거부된다() {
        assertThatThrownBy(() -> reconciliationJobTriggerService.reconcile(activeCoupon.getCouponId()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.RECONCILIATION_NOT_ALLOWED_YET);
    }

    @Test
    void 파이프라인이_드레인_안됐으면_예전과_같은_예외로_거부된다() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO issue_message
                            (coupon_id, user_id, sequence_no, message_key, topic, payload, status, retry_count, created_at)
                        VALUES (:couponId, 1, 1, 'trigger-svc-pending', 'coupon-issue-events', '{}', 'PENDING', 0, NOW(6))
                        """)
                        .setParameter("couponId", endedCoupon.getCouponId())
                        .executeUpdate());

        assertThatThrownBy(() -> reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.RECONCILIATION_PIPELINE_NOT_DRAINED);
    }

    @Test
    void 존재하지_않는_쿠폰이면_예전과_같은_예외로_거부된다() {
        assertThatThrownBy(() -> reconciliationJobTriggerService.reconcile(-1L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    // asOfAt을 매번 LocalDateTime.now()로만 새로 만들면 JobParameters가 절대 안 겹쳐서
    // Spring Batch의 재시작/중복실행 방지가 실제 API 경로에서는 전혀 동작하지 않는다 — 그래서
    // ReconciliationJobStateLookup으로 트리거 전에 상태를 직접 확인하도록 바꿨다. 이 두 테스트는
    // 그 판단 분기(COMPLETED면 새 실행 / RUNNING이면 즉시 거절)가 트리거 서비스 레벨에서
    // 실제로 동작하는지 확인한다. FAILED면 재시작(체크포인트 이어받기)하는 것 자체는
    // ReconciliationJobStateLookupTest(상태 판단)와 ReconciliationJobRestartTest(실제 이어받기)가
    // 이미 각각 검증한다.

    @Test
    void 이미_완료된_쿠폰을_다시_트리거하면_새_리포트로_새_실행이_생긴다() {
        ReconciliationBatchResult first = reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());
        ReconciliationBatchResult second = reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());

        assertThat(second.report().getReportId()).isNotEqualTo(first.report().getReportId());
        assertThat(second.report().getAsOfAt()).isAfter(first.report().getAsOfAt());
    }

    // 이력 목록(#154) — 트리거 응답은 그 순간에만 볼 수 있고, 나중에 다시 조회할 방법이
    // 없었다. 같은 쿠폰을 두 번 트리거해 리포트 2건을 만든 뒤, listHistory()가 최신순(asOfAt
    // DESC)으로 돌려주는지 확인한다.
    @Test
    void listHistory는_리포트를_최신순으로_반환한다() {
        ReconciliationBatchResult first = reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());
        ReconciliationBatchResult second = reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());

        List<ReconciliationReport> history =
                reconciliationJobTriggerService.listHistory(endedCoupon.getCouponId(), 10);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getReportId()).isEqualTo(second.report().getReportId());
        assertThat(history.get(1).getReportId()).isEqualTo(first.report().getReportId());
    }

    // 리포트가 계속 쌓여도 그래프/대시보드가 요청한 만큼만 가져오는지 — limit이 실제로
    // Pageable에 반영되는지 확인한다.
    @Test
    void listHistory는_limit만큼만_반환한다() {
        reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());
        reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());

        List<ReconciliationReport> history =
                reconciliationJobTriggerService.listHistory(endedCoupon.getCouponId(), 1);

        assertThat(history).hasSize(1);
    }

    // PR #155 리뷰 반영 — 존재하지 않는 쿠폰이면 빈 배열이 아니라 reconcile()과 동일하게
    // COUPON_NOT_FOUND를 던져야 한다. 그래야 "이력이 아직 없다"와 "쿠폰 자체가 없다"를
    // 호출하는 쪽이 구분할 수 있다.
    @Test
    void listHistory는_존재하지_않는_쿠폰이면_COUPON_NOT_FOUND를_던진다() {
        assertThatThrownBy(() -> reconciliationJobTriggerService.listHistory(-1L, 10))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void 이미_실행_중인_쿠폰에_트리거하면_Job을_시작하지_않고_바로_거절한다() {
        long plantedExecutionId = plantRunningExecution(endedCoupon.getCouponId());

        try {
            assertThatThrownBy(() -> reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId()))
                    .isInstanceOf(GeneralException.class)
                    .extracting(ex -> ((GeneralException) ex).getErrorCode())
                    .isEqualTo(CouponErrorCode.REQUEST_IN_PROGRESS);

            // 거절이 jobOperator.start() 이전에 일어나므로, reconciliation_report가 새로 생기지 않아야 한다.
            entityManager.clear();
            long reportCount = ((Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM reconciliation_report WHERE coupon_id = :couponId")
                    .setParameter("couponId", endedCoupon.getCouponId())
                    .getSingleResult()).longValue();
            assertThat(reportCount).isZero();
        } finally {
            removeBatchExecution(plantedExecutionId);
        }
    }

    // resolveJobParameters()의 "실행 중인지 확인 → asOfAt 결정"과 jobOperator.start() 사이에
    // 원자성이 없으면, 두 스레드가 동시에 조회를 통과해 각자 다른 asOfAt으로 서로 다른
    // JobParameters를 만들어 둘 다 실행돼버린다(JobExecutionAlreadyRunningException도 안 던져짐).
    // CyclicBarrier로 두 스레드를 같은 순간에 reconcile() 진입점까지 밀어넣어 이 경합을 실제로
    // 재현하고, 락(GET_LOCK)이 정확히 하나만 통과시키는지 확인한다.
    @Test
    void 동시_요청_두_개가_들어오면_하나만_통과하고_나머지는_즉시_거절된다() throws InterruptedException {
        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Future<ReconciliationBatchResult>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return reconciliationJobTriggerService.reconcile(endedCoupon.getCouponId());
            }));
        }

        int succeeded = 0;
        int rejected = 0;
        for (Future<ReconciliationBatchResult> future : futures) {
            try {
                future.get();
                succeeded++;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                assertThat(cause).isInstanceOf(GeneralException.class);
                assertThat(((GeneralException) cause).getErrorCode()).isEqualTo(CouponErrorCode.REQUEST_IN_PROGRESS);
                rejected++;
            }
        }
        executor.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
    }

    // ReconciliationJobStateLookupTest와 같은 방식으로 BATCH_JOB_* 테이블에 STARTED 상태인
    // 실행을 직접 심는다 — 실제로 Job이 도는 중인 상황을 흉내 낸다.
    private long plantRunningExecution(Long couponId) {
        return transactionTemplate.execute(status -> {
            long instanceId = nextBatchId("BATCH_JOB_INSTANCE_SEQ");
            long executionId = nextBatchId("BATCH_JOB_EXECUTION_SEQ");

            entityManager.createNativeQuery(
                    "INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) "
                            + "VALUES (:id, 0, 'reconciliationJob', :key)")
                    .setParameter("id", instanceId)
                    .setParameter("key", "trigger-svc-running-" + executionId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, STATUS) "
                            + "VALUES (:id, 0, :instanceId, NOW(6), 'STARTED')")
                    .setParameter("id", executionId)
                    .setParameter("instanceId", instanceId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "INSERT INTO BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING) "
                            + "VALUES (:id, 'couponId', 'java.lang.Long', :couponId, 'Y')")
                    .setParameter("id", executionId)
                    .setParameter("couponId", String.valueOf(couponId))
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "INSERT INTO BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING) "
                            + "VALUES (:id, 'asOfAt', 'java.time.LocalDateTime', :asOfAt, 'Y')")
                    .setParameter("id", executionId)
                    .setParameter("asOfAt", LocalDateTime.now().toString())
                    .executeUpdate();

            return executionId;
        });
    }

    private void removeBatchExecution(long executionId) {
        transactionTemplate.executeWithoutResult(status -> {
            Number instanceId = (Number) entityManager.createNativeQuery(
                    "SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                    .setParameter("id", executionId)
                    .getSingleResult();

            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID = :id")
                    .setParameter("id", executionId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                    .setParameter("id", executionId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id")
                    .setParameter("id", instanceId.longValue()).executeUpdate();
        });
    }

    private long nextBatchId(String seqTable) {
        entityManager.createNativeQuery("UPDATE " + seqTable + " SET ID = ID + 1").executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT ID FROM " + seqTable).getSingleResult()).longValue();
    }

    // status는 USED인데 이력은 ISSUED까지만 남겨서 historyMismatchStep이 집어갈
    // HISTORY_MISMATCH 대상 1건을 만든다(ReconciliationJobRestartTest의 동명 헬퍼와 같은 패턴).
    private void createMismatchedIssue(Coupon coupon, String couponCode) {
        AppUser user = AppUser.builder()
                .name("유저-" + couponCode).email(couponCode + "@test.com").phone("010-2222-2222")
                .role(UserRole.ROLE_MEMBER).build();
        entityManager.persist(user);

        CouponIssue issue = CouponIssue.builder()
                .coupon(coupon).user(user).sequenceNo(1)
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

    // 이 클래스 전체에 @Primary로 적용되지만, 위 주석대로 다른 테스트는 청크 Step에 아무것도
    // 안 넘어와 write()가 호출되지 않는다 — 상태(callCount 등) 없이 항상 실패해도 안전하다.
    @TestConfiguration
    static class PoisonWriterConfig {

        @Bean
        @Primary
        ItemWriter<VerificationDetail> poisonVerificationDetailItemWriter() {
            return (Chunk<? extends VerificationDetail> chunk) -> {
                throw new RuntimeException("의도적 실패 — Job 중간 실패 시 리포트 상태 테스트용");
            };
        }
    }
}
