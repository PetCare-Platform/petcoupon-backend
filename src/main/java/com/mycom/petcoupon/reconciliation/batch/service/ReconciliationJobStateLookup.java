package com.mycom.petcoupon.reconciliation.batch.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

/**
 * reconciliationJob이 이 couponId로 마지막에 어떤 상태로 끝났는지 Spring Batch 메타데이터
 * 테이블(BATCH_JOB_*)에서 직접 조회한다.
 *
 * ReconciliationJobTriggerService.reconcile()이 asOfAt = LocalDateTime.now()를 매 호출마다
 * 새로 만들면 JobParameters가 절대 안 겹쳐서, Spring Batch의 재시작/중복실행 방지(JobParameters
 * 완전일치 기반 JobInstance 식별)가 실제 API 경로에서는 전혀 동작하지 않는다 — 트리거하기 전에
 * 여기서 먼저 상태를 확인해야 그 두 가지를 실제로 강제할 수 있다.
 *
 * JobOperator.getRunningExecutions()/getParameters()로도 비슷한 정보를 얻을 수 있지만 문자열
 * 파싱이 필요해 취약하다 — BatchRepositoryConfig로 이미 JDBC 기반 JobRepository를 붙여놨으니
 * 그 테이블을 직접 조회하는 쪽이 더 정확하다.
 */
@Component
public class ReconciliationJobStateLookup {

    private static final String JOB_NAME = "reconciliationJob";

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<LatestExecution> findLatest(Long couponId) {
        try {
            Tuple row = (Tuple) entityManager.createNativeQuery("""
                    SELECT e.STATUS AS status,
                           (SELECT p2.PARAMETER_VALUE FROM BATCH_JOB_EXECUTION_PARAMS p2
                             WHERE p2.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
                               AND p2.PARAMETER_NAME = 'asOfAt') AS as_of_at
                      FROM BATCH_JOB_EXECUTION e
                      JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
                      JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
                     WHERE i.JOB_NAME = :jobName
                       AND p.PARAMETER_NAME = 'couponId'
                       AND p.PARAMETER_VALUE = :couponId
                     ORDER BY e.CREATE_TIME DESC
                     LIMIT 1
                    """, Tuple.class)
                    .setParameter("jobName", JOB_NAME)
                    .setParameter("couponId", String.valueOf(couponId))
                    .getSingleResult();

            String status = row.get("status", String.class);
            String asOfAtValue = row.get("as_of_at", String.class);

            return Optional.of(new LatestExecution(status, LocalDateTime.parse(asOfAtValue)));
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    public record LatestExecution(String status, LocalDateTime asOfAt) {

        // STARTING/STARTED/STOPPING — 지금 막 시작됐거나 도는 중. 새 트리거는 거절해야 한다.
        private static final Set<String> RUNNING_STATUSES = Set.of("STARTING", "STARTED", "STOPPING");

        public boolean isRunning() {
            return RUNNING_STATUSES.contains(status);
        }

        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }
    }
}
