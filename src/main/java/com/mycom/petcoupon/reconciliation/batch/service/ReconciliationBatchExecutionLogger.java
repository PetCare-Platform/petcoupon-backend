package com.mycom.petcoupon.reconciliation.batch.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;

import lombok.extern.slf4j.Slf4j;

/**
 * Job/Step 실행 결과를 로그로 남긴다. read/write/filter/skip/commit/rollback count는 Spring
 * Batch가 JobRepository(JDBC)에 이미 다 기록해두는 값이라 새로 추적하는 게 아니라, JobExecution/
 * StepExecution에서 꺼내 사람이 읽기 좋은 한 덩어리 로그로 정리하는 역할만 한다.
 */
@Slf4j
@Component
public class ReconciliationBatchExecutionLogger {

    public void log(JobExecution execution, ReconciliationBatchResult result) {
        LocalDateTime endTime = execution.getEndTime() != null ? execution.getEndTime() : LocalDateTime.now();
        long totalMs = execution.getStartTime() != null
                ? Duration.between(execution.getStartTime(), endTime).toMillis()
                : -1;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "[ReconciliationBatch] jobExecutionId=%d status=%s duration=%dms",
                execution.getId(), execution.getStatus(), totalMs));

        for (StepExecution step : execution.getStepExecutions()) {
            long stepMs = (step.getStartTime() != null && step.getEndTime() != null)
                    ? Duration.between(step.getStartTime(), step.getEndTime()).toMillis()
                    : -1;
            sb.append(String.format(
                    "%n  - step=%-22s status=%-9s duration=%5dms read=%d write=%d filter=%d skip=%d commit=%d rollback=%d",
                    step.getStepName(), step.getStatus(), stepMs,
                    step.getReadCount(), step.getWriteCount(), step.getFilterCount(),
                    step.getSkipCount(), step.getCommitCount(), step.getRollbackCount()));
        }

        if (result != null) {
            ReconciliationReport report = result.report();
            sb.append(String.format(
                    "%n  reportId=%d result=%s totalCount=%d errorCount=%d verificationDetails=%d byType=%s",
                    report.getReportId(), report.getResult(), report.getTotalCount(), report.getErrorCount(),
                    result.verificationDetailCount(), result.verificationDetailCountByType()));
        }

        log.info(sb.toString());
    }
}
