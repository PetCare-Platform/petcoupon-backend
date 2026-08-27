package com.mycom.petcoupon.reconciliation.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.reconciliation.batch.service.ReconciliationJobTriggerService;

@ExtendWith(MockitoExtension.class)
class ReconciliationSchedulerTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private ReconciliationJobTriggerService reconciliationJobTriggerService;

    @InjectMocks
    private ReconciliationScheduler scheduler;

    @Test
    void runScheduledReconciliation은_ENDED_쿠폰_전체를_순회하며_reconcile을_호출한다() {
        when(couponRepository.findCouponIdsByStatus(CouponStatus.ENDED)).thenReturn(List.of(1L, 2L, 3L));

        scheduler.runScheduledReconciliation();

        verify(reconciliationJobTriggerService).reconcile(1L);
        verify(reconciliationJobTriggerService).reconcile(2L);
        verify(reconciliationJobTriggerService).reconcile(3L);
    }

    // 한 쿠폰이 REQUEST_IN_PROGRESS 등으로 실패해도, 그 예외가 순회 전체를 끊어서 나머지
    // 쿠폰들이 검증을 건너뛰게 되면 안 된다 — ReconciliationScheduler.runScheduledReconciliation()
    // 주석에 명시된 요구사항을 그대로 검증한다.
    @Test
    void 한_쿠폰의_reconcile_실패가_나머지_쿠폰_검증을_막지_않는다() {
        when(couponRepository.findCouponIdsByStatus(CouponStatus.ENDED)).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new GeneralException(CouponErrorCode.REQUEST_IN_PROGRESS))
                .when(reconciliationJobTriggerService).reconcile(2L);

        scheduler.runScheduledReconciliation();

        verify(reconciliationJobTriggerService, times(1)).reconcile(1L);
        verify(reconciliationJobTriggerService, times(1)).reconcile(2L);
        verify(reconciliationJobTriggerService, times(1)).reconcile(3L);
    }
}
