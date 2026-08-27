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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
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
 */
@SpringBootTest(properties = {
        "event.status.scheduler.enabled=false",
        "coupon.status.enabled=false"
})
class ReconciliationJobTriggerServiceTest {

    @Autowired
    private ReconciliationJobTriggerService reconciliationJobTriggerService;

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
}
