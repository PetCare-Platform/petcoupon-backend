package com.mycom.petcoupon.coupon.service;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.dto.res.CouponLoadTestStatusResponse;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponIssueLoadTestSummary;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.messaging.repository.IssueStatusCount;

import lombok.RequiredArgsConstructor;

// 부하 테스트 현황 조회(#195) — load-test/sql/verify_issue_result.sql을 서비스로 옮긴 것.
// 0번 블록(파이프라인 상태) -> pending/sent/consumed/failed/dlq, 1번(발급 건수=MIN(접수,총재고))
// -> passed/overIssued, 2번(1인 2매) -> duplicateUsers, 4번(순번 연속) -> sequenceIntact,
// 13번(IN_PROGRESS 멱등키) -> inProgressIdempotencyKeys, 확정 소요초 -> elapsedSeconds.
//
// 단, rejected는 accepted - passed(뺄셈)가 아니라 최종 FAILED 확정 건을 직접 센다 — 뺄셈은
// "부하 종료 후"를 전제한 verify_issue_result.sql 원본 방식인데, 이 API는 부하 도중 5초마다
// 폴링돼서 처리 중인 pending/SENT 요청까지 거절로 잡히고 초과발급 시 음수가 났다(PR #195 리뷰 반영).
@Service
@RequiredArgsConstructor
public class CouponLoadTestStatusServiceImpl implements CouponLoadTestStatusService {

    private final CouponStockRepository couponStockRepository;
    private final IssueMessageRepository issueMessageRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Override
    @Transactional(readOnly = true)
    public CouponLoadTestStatusResponse getLoadTestStatus(Long couponId) {
        CouponStock couponStock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

        Map<IssueMessageStatus, Long> pipelineCounts = issueMessageRepository.countGroupedByStatusForCoupon(couponId)
                .stream()
                .collect(Collectors.toMap(IssueStatusCount::getStatus, IssueStatusCount::getCount));

        long accepted = idempotencyKeyRepository.countAcceptedByCouponId(couponId);
        long rejected = idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(
                couponId, IdempotencyStatus.FAILED
        );
        long inProgress = idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(couponId, IdempotencyStatus.IN_PROGRESS);

        CouponIssueLoadTestSummary summary = couponIssueRepository.summarizeForLoadTest(couponId);
        long passed = summary.getPassedCount();
        long expectedPassed = Math.min(accepted, couponStock.getTotalQuantity());

        return CouponLoadTestStatusResponse.builder()
                .accepted(accepted)
                .passed(passed)
                .rejected(rejected)
                .pending(countOf(pipelineCounts, IssueMessageStatus.PENDING))
                .sent(countOf(pipelineCounts, IssueMessageStatus.SENT))
                .consumed(countOf(pipelineCounts, IssueMessageStatus.CONSUMED))
                .failed(countOf(pipelineCounts, IssueMessageStatus.FAILED))
                .dlq(countOf(pipelineCounts, IssueMessageStatus.DLQ))
                .inProgressIdempotencyKeys(inProgress)
                .overIssued(passed > expectedPassed)
                .duplicateUsers(summary.getDuplicateUserCount())
                .sequenceIntact(summary.getSequenceIntact())
                .elapsedSeconds(summary.getElapsedSeconds() == null ? 0 : summary.getElapsedSeconds())
                .build();
    }

    private long countOf(Map<IssueMessageStatus, Long> counts, IssueMessageStatus status) {
        return counts.getOrDefault(status, 0L);
    }
}
