package com.mycom.petcoupon.reconciliation.batch.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.converter.ReconciliationConverter;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.reconciliation.repository.VerificationDetailRepository;

import lombok.RequiredArgsConstructor;

/**
 * AdminReconciliationController가 쓰는 트리거 진입점. reconciliationJob(Spring Batch)을
 * 동기로 끝까지 실행시키고(별도 TaskExecutor를 안 붙였으니 JobOperator.start()가 블로킹) 그
 * 결과를 ReconciliationReport로 돌려준다 — 컨트롤러/응답 DTO/이미 프론트에 전달된 API 계약은
 * 그대로 두고 내부 실행 방식만 Job으로 바꾸는 것이 목적이다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationJobTriggerService {

    private final JobOperator jobOperator;
    private final Job reconciliationJob;
    private final ReconciliationReportRepository reconciliationReportRepository;
    private final VerificationDetailRepository verificationDetailRepository;
    private final ReconciliationBatchExecutionLogger batchExecutionLogger;

    public ReconciliationBatchResult reconcile(Long couponId) {
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("couponId", couponId)
                .addLocalDateTime("asOfAt", LocalDateTime.now())
                .toJobParameters();

        JobExecution execution;
        try {
            execution = jobOperator.start(reconciliationJob, jobParameters);
        } catch (JobInstanceAlreadyCompleteException | JobExecutionAlreadyRunningException e) {
            // 같은 couponId로 같은 밀리초에 asOfAt이 겹치는 경우 — 사실상 동시 중복 요청이다.
            throw new GeneralException(CouponErrorCode.REQUEST_IN_PROGRESS);
        } catch (InvalidJobParametersException | JobRestartException e) {
            // JobParametersValidator나 non-restartable Step을 안 쓰는 지금 구성에선 실질적으로
            // 발생하지 않는 방어적 분기다 — "쿠폰 신청" 관련 코드를 재사용하면 메시지가 안 맞아서
            // 배치 실행 실패와 같은 코드를 쓴다.
            throw new GeneralException(CouponErrorCode.RECONCILIATION_BATCH_FAILED);
        }

        if (execution.getStatus() == BatchStatus.COMPLETED) {
            Long reportId = execution.getExecutionContext().getLong("reportId");
            ReconciliationBatchResult result = loadResult(reportId);
            batchExecutionLogger.log(execution, result);
            return result;
        }

        batchExecutionLogger.log(execution, null);
        throw resolveFailureException(execution);
    }

    // verificationDetails를 JOIN FETCH로 통째로 읽지 않는다 — 300만 건 규모에서 불일치가
    // 대량으로 나는 극단적 케이스에도 응답 조립 시점에 전체를 메모리에 올리지 않도록, 전체
    // 건수·응답용 상위 N건·로그용 타입별 집계를 각각 필요한 만큼만 조회한다.
    private ReconciliationBatchResult loadResult(Long reportId) {
        ReconciliationReport report = reconciliationReportRepository.findById(reportId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        long detailCount = verificationDetailRepository.countByReport_ReportId(reportId);

        List<VerificationDetail> topDetails = verificationDetailRepository.findByReport_ReportIdOrderByDetailIdAsc(
                reportId, PageRequest.of(0, ReconciliationConverter.MAX_DETAILS_IN_RESPONSE));

        Map<VerificationErrorType, Long> countByType = verificationDetailRepository
                .countGroupedByErrorType(reportId).stream()
                .collect(Collectors.toMap(
                        row -> (VerificationErrorType) row[0],
                        row -> (Long) row[1]
                ));

        return new ReconciliationBatchResult(report, detailCount, topDetails, countByType);
    }

    // Step(Tasklet) 안에서 GeneralException을 던져도 JobOperator.start()가 그걸 그대로 다시
    // 던지지 않고 JobExecution.status=FAILED로만 남긴다 — 여기서 원래 예외를 꺼내 다시 던져야
    // GlobalExceptionHandler가 예전과 같은 상태코드/에러코드로 응답한다.
    private RuntimeException resolveFailureException(JobExecution execution) {
        List<Throwable> failures = execution.getAllFailureExceptions();

        for (Throwable failure : failures) {
            if (failure instanceof GeneralException generalException) {
                return generalException;
            }
        }

        return new GeneralException(CouponErrorCode.RECONCILIATION_BATCH_FAILED);
    }
}
