package com.mycom.petcoupon.reconciliation.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

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
}
