package com.mycom.petcoupon.reconciliation.batch.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.sql.DataSource;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.converter.ReconciliationConverter;
import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.reconciliation.repository.VerificationDetailRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AdminReconciliationController가 쓰는 트리거 진입점. reconciliationJob(Spring Batch)을
 * 동기로 끝까지 실행시키고(별도 TaskExecutor를 안 붙였으니 JobOperator.start()가 블로킹) 그
 * 결과를 ReconciliationReport로 돌려준다 — 컨트롤러/응답 DTO/이미 프론트에 전달된 API 계약은
 * 그대로 두고 내부 실행 방식만 Job으로 바꾸는 것이 목적이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationJobTriggerService {

    private final JobOperator jobOperator;
    private final Job reconciliationJob;
    private final ReconciliationJobStateLookup jobStateLookup;
    private final ReconciliationReportRepository reconciliationReportRepository;
    private final VerificationDetailRepository verificationDetailRepository;
    private final ReconciliationBatchExecutionLogger batchExecutionLogger;
    private final CouponRepository couponRepository;

    // 메인 DataSource가 아니라 락 전용 소형 풀이다 — 이유는
    // ReconciliationLockDataSourceConfig 클래스 주석 참고.
    @Qualifier("reconciliationLockDataSource")
    private final DataSource lockDataSource;

    /**
     * resolveJobParameters()는 "실행 중인지 확인 → asOfAt 결정"을 하지만 이 조회와
     * jobOperator.start() 사이에는 원자성이 없다. 두 요청이 동시에 조회를 통과하면 각자
     * LocalDateTime.now()로 서로 다른 asOfAt을 만들어버려서, Spring Batch의 중복 실행 방지
     * (JobParameters 완전일치 기반 JobInstance 식별)가 둘을 아예 다른 실행으로 보고 둘 다
     * 통과시킨다 — JobExecutionAlreadyRunningException조차 안 던져진다.
     *
     * MySQL 세션 락(GET_LOCK)으로 "조회~start()" 구간 전체를 couponId 단위로 직렬화한다.
     * 대기 없이(timeout=0) 시도해서 이미 누가 잡고 있으면 즉시 REQUEST_IN_PROGRESS로 거절한다 —
     * 기존 "실행 중" 응답과 같은 사용자 경험을 유지하기 위함이다. 세션 단위 락이라
     * GET_LOCK/RELEASE_LOCK을 반드시 같은 Connection에서 호출해야 한다 — JPA/Hibernate는
     * 트랜잭션마다 커넥션을 다시 빌려줄 수 있어 EntityManager로는 이를 보장 못 한다.
     * DataSource에서 커넥션을 직접 하나 빌려 쓰는 이유다.
     */
    public ReconciliationBatchResult reconcile(Long couponId) {
        try (Connection lockConnection = lockDataSource.getConnection()) {
            if (!tryLock(lockConnection, couponId)) {
                throw new GeneralException(CouponErrorCode.REQUEST_IN_PROGRESS);
            }

            try {
                return doReconcile(couponId);
            } finally {
                unlock(lockConnection, couponId);
            }
        } catch (SQLException e) {
            throw new GeneralException(CouponErrorCode.RECONCILIATION_BATCH_FAILED);
        }
    }

    private ReconciliationBatchResult doReconcile(Long couponId) {
        JobParameters jobParameters = resolveJobParameters(couponId);

        JobExecution execution;
        try {
            execution = jobOperator.start(reconciliationJob, jobParameters);
        } catch (JobInstanceAlreadyCompleteException | JobExecutionAlreadyRunningException e) {
            // 위 락으로 조회~start()를 직렬화한 뒤에도 남겨두는 마지막 방어선이다 — 배치
            // 메타데이터에 남은 이전 실행 흔적과의 경합 등 락만으로 못 막는 경우를 대비한다.
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

        markReportFailedIfCreated(execution);
        batchExecutionLogger.log(execution, null);
        throw resolveFailureException(execution);
    }

    // reportInitStep이 이미 커밋해둔 ReconciliationReport row가 있다면(즉 실패가
    // reportInitStep 이후 Step에서 났다면) finishedAt을 채워 "실패로 끝남"을 남긴다.
    // preconditionCheckStep에서 실패했다면 reportId 자체가 없으니(report row 미생성) 아무것도
    // 하지 않는다. result는 ReportInitTasklet이 이미 ERROR로 넣어둔 값 그대로 둔다 — 검증이
    // 끝까지 못 갔다는 뜻을 그대로 유지해야 한다.
    private void markReportFailedIfCreated(JobExecution execution) {
        if (!execution.getExecutionContext().containsKey("reportId")) {
            return;
        }
        Long reportId = execution.getExecutionContext().getLong("reportId");
        reconciliationReportRepository.findById(reportId).ifPresent(report -> {
            // findById가 이미 자체 트랜잭션을 끝내고 반환한 detached 엔티티라 여기서 필드만
            // 바꿔서는 반영되지 않는다 — save()로 명시적으로 merge(UPDATE)해야 한다.
            report.markFailed(LocalDateTime.now());
            reconciliationReportRepository.save(report);
        });
    }

    // couponId별로 락 이름을 나눠서 다른 쿠폰의 실행은 서로 안 막는다. 대기하지 않는다
    // (timeout=0) — 이미 실행 중이면 기다리게 하지 않고 즉시 거절하는 게 기존 "실행 중"
    // 응답과 일관된다.
    private boolean tryLock(Connection connection, Long couponId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            ps.setString(1, lockName(couponId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    // 락은 반드시 잡았던 것과 같은 Connection에서 풀어야 한다 — 세션 단위 락이기 때문이다.
    // 여기서 실패하면(드묾) 커넥션이 풀에 반환된 뒤 재사용되면서 세션이 이어지므로 락이 그
    // 세션에 남을 수 있다 — 그렇다고 이번 요청 자체를 실패시키지는 않고 로그만 남긴다.
    private void unlock(Connection connection, Long couponId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, lockName(couponId));
            ps.executeQuery();
        } catch (SQLException e) {
            log.warn("[Reconciliation] couponId={} 락 해제 실패", couponId, e);
        }
    }

    private String lockName(Long couponId) {
        return "reconciliation:coupon:" + couponId;
    }

    // 이력 목록(#154) — reconcile()과 달리 배치를 새로 돌리지 않고, 이미 쌓인 리포트를 최신순으로
    // 조회만 한다. 트리거 응답은 그 순간에만 볼 수 있어서, 나중에 그래프/대시보드로 추이를 보려면
    // 이 조회 경로가 필요하다. DTO 변환은 컨트롤러+컨버터가 맡는다(reconcile() 흐름과 동일한 책임 분리).
    //
    // couponId 존재 확인을 reconcile()과 동일하게 COUPON_NOT_FOUND로 통일한다(PR #155 리뷰
    // 반영) — 원래는 존재하지 않는 쿠폰이어도 그냥 빈 배열을 반환했는데, 그러면 "이력이 아직
    // 없다"와 "쿠폰 자체가 없다"를 호출하는 쪽이 구분할 수 없었다.
    public List<ReconciliationReport> listHistory(Long couponId, int limit) {
        if (!couponRepository.existsById(couponId)) {
            throw new GeneralException(CouponErrorCode.COUPON_NOT_FOUND);
        }

        return reconciliationReportRepository.findByCoupon_CouponIdOrderByAsOfAtDesc(
                couponId, PageRequest.of(0, limit));
    }

    // asOfAt을 무조건 LocalDateTime.now()로 새로 만들면 이 couponId로 두 번 다시 같은
    // JobParameters가 나올 수 없어서, Spring Batch의 재시작/중복실행 방지(JobParameters
    // 완전일치 기반 JobInstance 식별)가 실제 API 경로에서는 아예 동작하지 않는다.
    // 그래서 트리거 직전에 이 couponId의 최근 실행 상태를 먼저 확인하고 asOfAt을 정한다.
    private JobParameters resolveJobParameters(Long couponId) {
        LocalDateTime asOfAt = LocalDateTime.now();

        Optional<ReconciliationJobStateLookup.LatestExecution> latest = jobStateLookup.findLatest(couponId);
        if (latest.isPresent()) {
            ReconciliationJobStateLookup.LatestExecution execution = latest.get();

            if (execution.isRunning()) {
                // 지금 막 시작됐거나 도는 중 — jobOperator.start()까지 갈 필요 없이 여기서 바로 막는다.
                throw new GeneralException(CouponErrorCode.REQUEST_IN_PROGRESS);
            }

            if (!execution.isCompleted()) {
                // FAILED/STOPPED 등 미완료 — 원래 asOfAt을 그대로 재사용해야 Spring Batch가
                // 이번 start()를 "재시작"으로 인식해 완료된 Step을 건너뛴다. 새 asOfAt을 쓰면
                // 완전히 별개의 새 JobInstance가 되어 재시작이 아니라 처음부터 다시 도는 것과 같아진다.
                asOfAt = execution.asOfAt();
            }
            // COMPLETED면 새 asOfAt으로 새 실행 — 이미 끝난 쿠폰을 나중에 다시 검증하려는
            // 정상적인 요청이다.
        }

        return new JobParametersBuilder()
                .addLong("couponId", couponId)
                .addLocalDateTime("asOfAt", asOfAt)
                .toJobParameters();
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
