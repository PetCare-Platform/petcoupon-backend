package com.mycom.petcoupon.reconciliation.batch.config;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.mycom.petcoupon.reconciliation.batch.chunk.HistoryMismatchItemProcessor;
import com.mycom.petcoupon.reconciliation.batch.chunk.HistoryMismatchRow;
import com.mycom.petcoupon.reconciliation.batch.chunk.InvalidTransitionItemProcessor;
import com.mycom.petcoupon.reconciliation.batch.chunk.InvalidTransitionRow;
import com.mycom.petcoupon.reconciliation.batch.tasklet.FinalizeReportTasklet;
import com.mycom.petcoupon.reconciliation.batch.tasklet.PreconditionCheckTasklet;
import com.mycom.petcoupon.reconciliation.batch.tasklet.RemainingChecksTasklet;
import com.mycom.petcoupon.reconciliation.batch.tasklet.ReportInitTasklet;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;

import lombok.RequiredArgsConstructor;

/**
 * 정합성 검증 배치. 6개 검증 규칙 자체는 ReconciliationDetectionQueries에 그대로 있고,
 * 여기서는 그 규칙들을 Job → Step → (일부는 청크) 구조로 실행하는 순서만 조립한다.
 *
 * 순서가 중요하다: remainingChecksStep(소량)이 반드시 historyMismatchStep/invalidTransitionStep
 * (청크, 대용량)보다 먼저 와야 한다 — RemainingChecksTasklet가 쓰는 assignReport()는 부모
 * report의 지연로딩 컬렉션에 append하는데, 청크 Step이 먼저 대량으로 써놨으면 그 전체를
 * 메모리로 끌어오게 된다.
 *
 * historyMismatchStep/invalidTransitionStep의 Reader는 JdbcPagingItemReader다 — MySQL
 * provider는 OFFSET이 아니라 정렬 키(coupon_issue_id/history_id) 기준 keyset 방식으로 다음
 * 페이지를 가져와서, 300만 건 규모에서도 뒤 페이지로 갈수록 느려지지 않는다.
 */
@Configuration
@RequiredArgsConstructor
public class ReconciliationJobConfig {

    @Value("${reconciliation.batch.chunk-size:1000}")
    private int chunkSize;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;

    private final PreconditionCheckTasklet preconditionCheckTasklet;
    private final ReportInitTasklet reportInitTasklet;
    private final RemainingChecksTasklet remainingChecksTasklet;
    private final FinalizeReportTasklet finalizeReportTasklet;
    // 인터페이스 타입으로 받는다 — 재시작(체크포인트 이어받기) 테스트에서 이 지점에 의도적으로
    // 실패하는 Writer를 끼워넣어야 하는데, 구체 타입으로 주입받으면 테스트 컨텍스트에서
    // @Primary 빈으로 갈아끼울 수 없다.
    private final ItemWriter<VerificationDetail> verificationDetailItemWriter;
    private final HistoryMismatchItemProcessor historyMismatchItemProcessor;
    private final InvalidTransitionItemProcessor invalidTransitionItemProcessor;

    @Bean
    public Job reconciliationJob() {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(preconditionCheckStep())
                .next(reportInitStep())
                .next(remainingChecksStep())
                .next(historyMismatchStep())
                .next(invalidTransitionStep())
                .next(finalizeReportStep())
                .build();
    }

    // allowStartIfComplete(true) — 이 Step만은 재시작해도 건너뛰지 않고 매번 다시 돈다.
    // 기본값(false)이면 1차 실행에서 이미 COMPLETED된 Step은 같은 JobInstance로 재시작할 때
    // Spring Batch가 그대로 건너뛴다. 이 Step은 드레인 여부를 확인만 하고 아무것도 쓰지 않아
    // 다시 돌아도 중복 부작용이 없다 — 반면 뒤쪽 Step(reportInitStep 등)은 리포트 row를 만드는
    // 부작용이 있어 재실행되면 안 된다(체크포인트 이어받기가 깨짐). 그래서 이 Step에만 건다.
    //
    // 1차 실행이 뒤쪽 Step에서 실패한 뒤 DLQ 재처리·늦은 Kafka 메시지로 파이프라인이 다시
    // 활성화될 수 있다 — 이 Step을 건너뛰면 그 사이 재오염된 상태를 못 잡고 재시작이 그대로
    // 이어져 버린다.
    @Bean
    public Step preconditionCheckStep() {
        return new StepBuilder("preconditionCheckStep", jobRepository)
                .tasklet(preconditionCheckTasklet, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    public Step reportInitStep() {
        return new StepBuilder("reportInitStep", jobRepository)
                .tasklet(reportInitTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step remainingChecksStep() {
        return new StepBuilder("remainingChecksStep", jobRepository)
                .tasklet(remainingChecksTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step historyMismatchStep() {
        return new ChunkOrientedStepBuilder<HistoryMismatchRow, VerificationDetail>("historyMismatchStep", jobRepository, chunkSize)
                .transactionManager(transactionManager)
                .reader(historyMismatchReader(null, null))
                .processor(historyMismatchItemProcessor)
                .writer(verificationDetailItemWriter)
                .build();
    }

    // HISTORY_MISMATCH: 현재 status가 가장 최근 이력의 to_status와 다른 건.
    // ReconciliationDetectionQueries의 다른 검증들과 조건 형식은 같지만, 대용량이라 전체 로드
    // 대신 coupon_issue_id 기준 keyset 페이징으로 chunkSize씩 읽는다.
    //
    // "최근 이력"을 h.history_id = (상관 서브쿼리 MAX(...)) 형태로 짰더니, EXPLAIN에서
    // coupon_issue_history에 이미 있는 FK 인덱스(coupon_issue_id)를 두고도 DEPENDENT SUBQUERY로
    // 잡혀 외부 coupon_issue 행마다 coupon_issue_history를 매번 통째로 스캔했다(type=ALL) —
    // 인덱스를 더 추가해도 옵티마이저가 상관 서브쿼리 형태 자체를 못 벗어나 소용없었다.
    // ROW_NUMBER() 윈도우 함수로 파생 테이블을 한 번만 만들어 조인하는 형태로 바꾸니
    // select_type이 DEPENDENT SUBQUERY(행마다 반복) 대신 DERIVED(한 번만 실행)로 바뀌었다 —
    // 50,000건 규모로 EXPLAIN/결과 일치까지 직접 확인함.
    //
    // 이력(h) 쪽에는 asOfAt 제한을 걸지 않는다 — ci.status는 애초에 "현재" 값만 존재하고
    // asOfAt 시점 값으로 복원할 방법이 없다(coupon_issue_history 말고는 시점별 상태를 남기는
    // 데가 없음). 그런데 이력만 asOfAt으로 자르면, asOfAt 이후 실제로 일어난 정상적인 상태
    // 전이(발급기간 종료 후에도 사용·만료는 계속 일어날 수 있다 — 사전조건이 요구하는 건
    // coupon.status=ENDED뿐이지 "더 이상 아무 일도 안 일어남"이 아니다)를 통째로 놓치고,
    // 오래된 이력과 최신 status를 비교해 정상 건을 불일치로 오탐한다(예: asOfAt 시점엔
    // ISSUED였는데 그 뒤 정상적으로 USED가 된 건 — 이력=ISSUED, status=USED로 잘못 잡힘).
    // ci와 h 둘 다 "지금" 기준으로 맞춰 비교해야, 이 검증의 원래 목적(상태 컬럼이 이력 기록
    // 없이 어긋난 진짜 정합성 버그만 잡는 것)에 맞는다. 바깥의 ci.created_at <= :asOfAt는
    // 그대로 둔다 — "이번 회차에 존재했던 발급 건"만 검증 대상으로 좁히는 것과는 별개다.
    @Bean
    @StepScope
    public JdbcPagingItemReader<HistoryMismatchRow> historyMismatchReader(
            @Value("#{jobParameters['couponId']}") Long couponId,
            @Value("#{jobParameters['asOfAt']}") LocalDateTime asOfAt) {
        try {
            SqlPagingQueryProviderFactoryBean queryProviderFactory = new SqlPagingQueryProviderFactoryBean();
            queryProviderFactory.setDataSource(dataSource);
            queryProviderFactory.setSelectClause("ci.coupon_issue_id, ci.user_id, ci.status, h.to_status");
            queryProviderFactory.setFromClause("""
                    coupon_issue ci
                    LEFT JOIN (
                        SELECT coupon_issue_id, to_status,
                               ROW_NUMBER() OVER (PARTITION BY coupon_issue_id ORDER BY history_id DESC) AS rn
                          FROM coupon_issue_history
                         WHERE coupon_id = :couponId
                    ) h ON h.coupon_issue_id = ci.coupon_issue_id AND h.rn = 1
                    """);
            queryProviderFactory.setWhereClause("""
                    ci.coupon_id = :couponId
                      AND ci.created_at <= :asOfAt
                      AND (h.to_status IS NULL OR ci.status <> h.to_status)
                    """);
            queryProviderFactory.setSortKey("ci.coupon_issue_id");

            Map<String, Object> params = new HashMap<>();
            params.put("couponId", couponId);
            params.put("asOfAt", asOfAt);

            JdbcPagingItemReader<HistoryMismatchRow> reader =
                    new JdbcPagingItemReader<>(dataSource, queryProviderFactory.getObject());
            reader.setName("historyMismatchReader");
            reader.setParameterValues(params);
            reader.setPageSize(chunkSize);
            reader.setRowMapper((rs, rowNum) -> new HistoryMismatchRow(
                    rs.getLong("coupon_issue_id"),
                    rs.getLong("user_id"),
                    rs.getString("status"),
                    rs.getString("to_status")
            ));
            reader.afterPropertiesSet();
            return reader;
        } catch (Exception e) {
            throw new IllegalStateException("historyMismatchReader 초기화에 실패했습니다.", e);
        }
    }

    @Bean
    public Step invalidTransitionStep() {
        return new ChunkOrientedStepBuilder<InvalidTransitionRow, VerificationDetail>("invalidTransitionStep", jobRepository, chunkSize)
                .transactionManager(transactionManager)
                .reader(invalidTransitionReader(null, null))
                .processor(invalidTransitionItemProcessor)
                .writer(verificationDetailItemWriter)
                .build();
    }

    // INVALID_STATUS: 화이트리스트 밖의 (from_status, to_status) 조합.
    // history_id 기준 keyset 페이징으로 chunkSize씩 읽는다.
    @Bean
    @StepScope
    public JdbcPagingItemReader<InvalidTransitionRow> invalidTransitionReader(
            @Value("#{jobParameters['couponId']}") Long couponId,
            @Value("#{jobParameters['asOfAt']}") LocalDateTime asOfAt) {
        try {
            SqlPagingQueryProviderFactoryBean queryProviderFactory = new SqlPagingQueryProviderFactoryBean();
            queryProviderFactory.setDataSource(dataSource);
            queryProviderFactory.setSelectClause("h.history_id, h.coupon_issue_id, h.user_id, h.from_status, h.to_status");
            // coupon_issue_history에 coupon_id가 이미 비정규화돼 있어서(FK가 아니라 컬럼으로),
            // coupon_issue와 JOIN 없이 h.coupon_id로 직접 필터링한다 — coupon_issue_history의
            // idx_history_coupon_id(coupon_id, history_id) 인덱스를 태우려면 이렇게 h.coupon_id를
            // 직접 조건에 써야 한다. JOIN을 거쳐 ci.coupon_id로 필터링하면 이 인덱스를 못 탄다
            // (EXPLAIN으로 확인함 — JOIN 버전은 인덱스가 있어도 history_id PK 풀스캔으로 빠졌다).
            queryProviderFactory.setFromClause("coupon_issue_history h");
            queryProviderFactory.setWhereClause("""
                    h.coupon_id = :couponId
                      AND h.created_at <= :asOfAt
                      AND NOT (
                          (h.from_status = 'NONE'   AND h.to_status = 'ISSUED')  OR
                          (h.from_status = 'ISSUED' AND h.to_status = 'USED')    OR
                          (h.from_status = 'ISSUED' AND h.to_status = 'EXPIRED') OR
                          (h.from_status = 'USED'   AND h.to_status = 'ISSUED')
                      )
                    """);
            queryProviderFactory.setSortKey("h.history_id");

            Map<String, Object> params = new HashMap<>();
            params.put("couponId", couponId);
            params.put("asOfAt", asOfAt);

            JdbcPagingItemReader<InvalidTransitionRow> reader =
                    new JdbcPagingItemReader<>(dataSource, queryProviderFactory.getObject());
            reader.setName("invalidTransitionReader");
            reader.setParameterValues(params);
            reader.setPageSize(chunkSize);
            reader.setRowMapper((rs, rowNum) -> new InvalidTransitionRow(
                    rs.getLong("coupon_issue_id"),
                    rs.getLong("user_id"),
                    rs.getString("from_status"),
                    rs.getString("to_status")
            ));
            reader.afterPropertiesSet();
            return reader;
        } catch (Exception e) {
            throw new IllegalStateException("invalidTransitionReader 초기화에 실패했습니다.", e);
        }
    }

    @Bean
    public Step finalizeReportStep() {
        return new StepBuilder("finalizeReportStep", jobRepository)
                .tasklet(finalizeReportTasklet, transactionManager)
                .build();
    }
}
