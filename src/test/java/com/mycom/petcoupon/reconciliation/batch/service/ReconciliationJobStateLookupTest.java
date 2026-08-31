package com.mycom.petcoupon.reconciliation.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * BATCH_JOB_* 메타데이터 테이블을 직접 조회하는 로직만 떼어 검증한다 — 실제 Job을 돌리지 않고
 * 행을 직접 심어서, findLatest()가 상태별로 옳게 판단하는지만 빠르게 확인한다.
 * (재시작 시 실제로 체크포인트를 이어받는지는 ReconciliationJobRestartTest가 이미 검증한다.)
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@SpringBootTest
class ReconciliationJobStateLookupTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private ReconciliationJobStateLookup jobStateLookup;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;
    private long instanceId;
    private long executionId;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(platformTransactionManager);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID = :id")
                    .setParameter("id", executionId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                    .setParameter("id", executionId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id")
                    .setParameter("id", instanceId).executeUpdate();
        });
    }

    @Test
    void 이_쿠폰으로_실행된_적이_없으면_빈_값을_돌려준다() {
        Optional<ReconciliationJobStateLookup.LatestExecution> result = jobStateLookup.findLatest(-999_999L);

        assertThat(result).isEmpty();
    }

    @Test
    void 최근_실행이_COMPLETED면_isCompleted가_true다() {
        Long couponId = plantExecution(9_100_001L, "COMPLETED");

        Optional<ReconciliationJobStateLookup.LatestExecution> result = jobStateLookup.findLatest(couponId);

        assertThat(result).isPresent();
        assertThat(result.get().isCompleted()).isTrue();
        assertThat(result.get().isRunning()).isFalse();
    }

    @Test
    void 최근_실행이_FAILED면_재시작_대상으로_판단하고_asOfAt을_그대로_돌려준다() {
        LocalDateTime originalAsOfAt = LocalDateTime.now().minusHours(1).withNano(0);
        Long couponId = plantExecution(9_100_002L, "FAILED", originalAsOfAt);

        Optional<ReconciliationJobStateLookup.LatestExecution> result = jobStateLookup.findLatest(couponId);

        assertThat(result).isPresent();
        assertThat(result.get().isCompleted()).isFalse();
        assertThat(result.get().isRunning()).isFalse();
        assertThat(result.get().asOfAt()).isEqualTo(originalAsOfAt);
    }

    @Test
    void 최근_실행이_STARTED면_실행중으로_판단한다() {
        Long couponId = plantExecution(9_100_003L, "STARTED");

        Optional<ReconciliationJobStateLookup.LatestExecution> result = jobStateLookup.findLatest(couponId);

        assertThat(result).isPresent();
        assertThat(result.get().isRunning()).isTrue();
    }

    @Test
    void 여러_실행_중_가장_최근_것만_본다() {
        // 같은 couponId로 오래된 FAILED 실행 하나, 최근 COMPLETED 실행 하나를 심는다 —
        // 최근 것(COMPLETED)만 반영돼야 한다.
        Long couponId = 9_100_004L;
        plantExecution(couponId, "FAILED", LocalDateTime.now().minusDays(1));

        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID = :id")
                    .setParameter("id", executionId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                    .setParameter("id", executionId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id")
                    .setParameter("id", instanceId).executeUpdate();
        });

        plantExecution(couponId, "COMPLETED", LocalDateTime.now());

        Optional<ReconciliationJobStateLookup.LatestExecution> result = jobStateLookup.findLatest(couponId);

        assertThat(result).isPresent();
        assertThat(result.get().isCompleted()).isTrue();
    }

    private Long plantExecution(long couponId, String status) {
        return plantExecution(couponId, status, LocalDateTime.now());
    }

    private Long plantExecution(long couponId, String status, LocalDateTime asOfAt) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            instanceId = nextId("BATCH_JOB_INSTANCE_SEQ");
            executionId = nextId("BATCH_JOB_EXECUTION_SEQ");

            entityManager.createNativeQuery(
                    "INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) "
                            + "VALUES (:id, 0, 'reconciliationJob', :key)")
                    .setParameter("id", instanceId)
                    .setParameter("key", "test-key-" + executionId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, STATUS) "
                            + "VALUES (:id, 0, :instanceId, NOW(6), :status)")
                    .setParameter("id", executionId)
                    .setParameter("instanceId", instanceId)
                    .setParameter("status", status)
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
                    .setParameter("asOfAt", FORMAT.format(asOfAt))
                    .executeUpdate();
        });

        return couponId;
    }

    private long nextId(String seqTable) {
        entityManager.createNativeQuery("UPDATE " + seqTable + " SET ID = ID + 1").executeUpdate();
        Number id = (Number) entityManager.createNativeQuery("SELECT ID FROM " + seqTable).getSingleResult();
        return id.longValue();
    }
}
