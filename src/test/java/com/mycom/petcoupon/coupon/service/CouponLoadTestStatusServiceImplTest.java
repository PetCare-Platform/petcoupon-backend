package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.dto.res.CouponLoadTestStatusResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.coupon.repository.CouponIssueLoadTestSummary;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;
import com.mycom.petcoupon.messaging.repository.IssueStatusCount;

@ExtendWith(MockitoExtension.class)
class CouponLoadTestStatusServiceImplTest {

    private static final Long COUPON_ID = 1L;

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private IssueMessageRepository issueMessageRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponIssueLuaService couponIssueLuaService;

    @InjectMocks
    private CouponLoadTestStatusServiceImpl couponLoadTestStatusService;

    private static IssueStatusCount issueStatusCount(IssueMessageStatus status, long count) {
        return new IssueStatusCount() {
            @Override
            public IssueMessageStatus getStatus() {
                return status;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    private static CouponIssueLoadTestSummary summary(long passed, long duplicateUsers, boolean sequenceIntact, Long elapsedSeconds) {
        return new CouponIssueLoadTestSummary() {
            @Override
            public long getPassedCount() {
                return passed;
            }

            @Override
            public long getDuplicateUserCount() {
                return duplicateUsers;
            }

            @Override
            public Long getSequenceIntact() {
                // [#200 버그 수정] 실제 MySQL은 IF(...)를 BIGINT(Long)로 내려준다 — 여기서도
                // boolean이 아니라 Long을 반환해서 실제 프로덕션 타입과 맞춘다.
                return sequenceIntact ? 1L : 0L;
            }

            @Override
            public Long getElapsedSeconds() {
                return elapsedSeconds;
            }
        };
    }

    private static CouponIssueRealtimeStock realtimeStock(boolean initialized, int remainingStock) {
        return CouponIssueRealtimeStock.builder()
                .initialized(initialized)
                .remainingStock(remainingStock)
                .build();
    }

    @Test
    void getLoadTestStatus_returnsResponse_whenCouponExists() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(100).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of(
                issueStatusCount(IssueMessageStatus.CONSUMED, 8),
                issueStatusCount(IssueMessageStatus.DLQ, 1)
        ));
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(10L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(COUPON_ID, IdempotencyStatus.FAILED))
                .thenReturn(2L);
        // 발행 8건(consumed) 중 1건은 소비 실패로 DLQ까지 갔지만, 그것도 발행은 된 것이라
        // countPublishedByCoupon()은 9(consumed 8 + dlq 1)를 돌려준다 — 계산 로직 자체는
        // IssueMessageRepositoryTest에서 실제 쿼리로 검증한다.
        when(issueMessageRepository.countPublishedByCoupon(COUPON_ID)).thenReturn(9L);
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(8, 0, true, 12L));
        when(couponIssueLuaService.getRealtimeStock(COUPON_ID)).thenReturn(realtimeStock(true, 0));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.accepted()).isEqualTo(10);
        assertThat(response.passed()).isEqualTo(8);
        assertThat(response.rejected()).isEqualTo(2);
        assertThat(response.consumed()).isEqualTo(8);
        assertThat(response.dlq()).isEqualTo(1);
        assertThat(response.published()).isEqualTo(9);
        assertThat(response.pending()).isZero();
        assertThat(response.overIssued()).isFalse();
        assertThat(response.sequenceIntact()).isTrue();
        assertThat(response.elapsedSeconds()).isEqualTo(12);
    }

    @Test
    void getLoadTestStatus_throwsException_whenCouponNotFound() {
        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponLoadTestStatusService.getLoadTestStatus(COUPON_ID))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void getLoadTestStatus_defaultsElapsedSecondsToZero_whenNoIssuesYet() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(100).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of());
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(COUPON_ID, IdempotencyStatus.FAILED))
                .thenReturn(0L);
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(0, 0, true, null));
        when(couponIssueLuaService.getRealtimeStock(COUPON_ID)).thenReturn(realtimeStock(true, 0));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.elapsedSeconds()).isZero();
    }

    @Test
    void getLoadTestStatus_marksOverIssued_whenPassedExceedsExpected() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(5).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of());
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(5L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(COUPON_ID, IdempotencyStatus.FAILED))
                .thenReturn(0L);
        // 총재고(5)·접수(5) 모두 5인데 실제 발급이 6건 — 초과발급 상황
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(6, 0, true, 3L));
        when(couponIssueLuaService.getRealtimeStock(COUPON_ID)).thenReturn(realtimeStock(true, 0));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.overIssued()).isTrue();
        // [PR #195 리뷰 반영] rejected를 accepted - passed(5 - 6)로 구했다면 -1이 나왔을 상황.
        // 최종 FAILED 확정 건을 직접 세므로(이 테스트에선 0건) 초과발급이어도 음수가 나오지 않는다.
        assertThat(response.rejected()).isZero();
    }

    // [PR #195 리뷰 반영] 부하 도중엔 아직 pending/SENT(파이프라인 처리 중)인 요청이 존재한다.
    // rejected를 accepted - passed로 구하면 이런 처리 중 요청까지 거절로 잡혔다 — 최종 FAILED
    // 확정 건만 직접 세면 처리 중 요청 수와 무관해야 한다.
    @Test
    void getLoadTestStatus_excludesInFlightRequests_fromRejected() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(100).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        // 접수 10건 중 아직 7건이 pending/SENT로 파이프라인 처리 중 — 확정된 건 없음
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of(
                issueStatusCount(IssueMessageStatus.PENDING, 5),
                issueStatusCount(IssueMessageStatus.SENT, 2)
        ));
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(10L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(COUPON_ID, IdempotencyStatus.FAILED))
                .thenReturn(0L);
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(0, 0, true, null));
        when(couponIssueLuaService.getRealtimeStock(COUPON_ID)).thenReturn(realtimeStock(true, 0));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.pending()).isEqualTo(5);
        assertThat(response.sent()).isEqualTo(2);
        // accepted(10) - passed(0) = 10이었다면 처리 중인 7건까지 거절로 잘못 잡혔을 상황
        assertThat(response.rejected()).isZero();
    }

    // [#200 버그 수정] getSequenceIntact()가 Long(0)을 돌려주는 "끊김" 케이스가 boolean으로
    // 제대로 변환되는지 확인한다 — 이전엔 인터페이스가 boolean이라 이 값 자체가 실제
    // MySQL에서 절대 못 내려오고 UnsupportedOperationException으로 500이 났었다.
    @Test
    void getLoadTestStatus_marksSequenceNotIntact_whenSummaryReturnsZero() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(100).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of());
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(COUPON_ID, IdempotencyStatus.FAILED))
                .thenReturn(0L);
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(0, 0, false, null));
        when(couponIssueLuaService.getRealtimeStock(COUPON_ID)).thenReturn(realtimeStock(true, 0));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.sequenceIntact()).isFalse();
    }

    // 깔때기의 "재고 통과"는 Redis Lua 가 실제로 걸러낸 수라, 파이프라인 맨 끝에서 세는 passed 와
    // 부하 도중 값이 다르다. 상류 단계가 하류보다 작아 보이는 역전을 막는 것이 이 필드의 목적이다.
    @Test
    void getLoadTestStatus_stockPassed는_Redis_재고에서_역산한다() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(10000).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of());
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(20000L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(COUPON_ID, IdempotencyStatus.FAILED))
                .thenReturn(0L);
        when(issueMessageRepository.countPublishedByCoupon(COUPON_ID)).thenReturn(10000L);
        // 재고는 이미 소진됐지만 DB 확정은 4,200건까지만 진행된 상태
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(4200, 0, true, 5L));
        when(couponIssueLuaService.getRealtimeStock(COUPON_ID)).thenReturn(realtimeStock(true, 0));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.stockPassed()).isEqualTo(10000);
        // passed 는 확정된 발급 수 그대로여야 한다 — 판정과 손실 계산이 이 값을 쓴다
        assertThat(response.passed()).isEqualTo(4200);
        assertThat(response.overIssued()).isFalse();
    }

    // 재고 키가 없을 때 totalQuantity 를 그대로 빼면 "전량 통과"로 보인다.
    @Test
    void getLoadTestStatus_stockPassed는_재고_키가_없으면_0이다() {
        CouponStock couponStock = CouponStock.builder().coupon(Coupon.builder().build()).totalQuantity(10000).build();

        when(couponStockRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponStock));
        when(issueMessageRepository.countGroupedByStatusForCoupon(COUPON_ID)).thenReturn(List.of());
        when(idempotencyKeyRepository.countAcceptedByCouponId(COUPON_ID)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatus(COUPON_ID, IdempotencyStatus.IN_PROGRESS)).thenReturn(0L);
        when(idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(COUPON_ID, IdempotencyStatus.FAILED))
                .thenReturn(0L);
        when(issueMessageRepository.countPublishedByCoupon(COUPON_ID)).thenReturn(0L);
        when(couponIssueRepository.summarizeForLoadTest(COUPON_ID)).thenReturn(summary(0, 0, true, null));
        when(couponIssueLuaService.getRealtimeStock(COUPON_ID)).thenReturn(realtimeStock(false, 0));

        CouponLoadTestStatusResponse response = couponLoadTestStatusService.getLoadTestStatus(COUPON_ID);

        assertThat(response.stockPassed()).isZero();
    }
}
