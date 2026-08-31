package com.mycom.petcoupon.reconciliation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.reconciliation.repository.ReconciliationReportRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * ReconciliationScheduler가 실제로 자동 실행되는지 검증한다(#154). 실제 운영 주기(기본 30분)를
 * 기다릴 수 없으므로, 이 테스트에서만 프로퍼티로 주기를 500ms로 낮추고 Awaitility로 폴링한다 —
 * CouponStatusSchedulerIntegrationTest와 같은 패턴이다.
 *
 * 실행 전 MySQL/Redis가 떠 있어야 한다: docker compose up -d
 */
// 이 컨텍스트를 캐싱해서 재사용하면, 500ms 주기 스케줄러가 클래스 종료 후에도 백그라운드에서
// 계속 돌면서 이후 실행되는 다른 정합성 테스트(예: ReconciliationJobTriggerServiceTest)의
// ENDED 쿠폰까지 몰래 재검증해버릴 수 있다 — 그 테스트들이 "정확히 N건, 정확히 이 reportId"를
// 기대하는 assertion을 타이밍에 따라 깨뜨릴 수 있다. 클래스가 끝나면 컨텍스트를 강제로 닫아서
// TaskScheduler(및 그 스레드)까지 같이 정리되게 한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "coupon.reconciliation.schedule-fixed-delay-ms=500",
        "coupon.reconciliation.schedule-initial-delay-ms=0",
        "event.status.scheduler.enabled=false",
        "coupon.status.enabled=false",
        "coupon.issue.stream.key=coupon:issue:stream:reconciliation-scheduler-test",
        "coupon.issue.stream.group=reconciliation-scheduler-test-group"
})
class ReconciliationSchedulerIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ReconciliationReportRepository reconciliationReportRepository;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    private TransactionTemplate transactionTemplate;
    private Coupon endedCoupon;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(platformTransactionManager);
        transactionTemplate.executeWithoutResult(status -> setUpData());
    }

    private void setUpData() {
        AppUser admin = AppUser.builder()
                .name("스케줄러 정합성 테스트 관리자").email("reconciliation-scheduler@test.com")
                .phone("010-4444-4444").role(UserRole.ROLE_ADMIN).build();
        entityManager.persist(admin);

        Event event = Event.builder()
                .createdBy(admin).name("스케줄러 정합성 테스트 이벤트").description("d")
                .openAt(LocalDateTime.now()).closeAt(LocalDateTime.now().plusDays(1)).build();
        entityManager.persist(event);

        endedCoupon = Coupon.builder()
                .event(event).name("스케줄러 정합성 테스트 ENDED 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1000).minOrderAmount(1000)
                .issueStartAt(LocalDateTime.now()).issueEndAt(LocalDateTime.now().plusDays(1))
                .validDays(7).build();
        entityManager.persist(endedCoupon);
        entityManager.flush();

        // 스케줄러가 ENDED 쿠폰만 대상으로 순회한다 — PreconditionCheckTasklet과 같은 기준.
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
        Long couponId = endedCoupon.getCouponId();
        Long eventId = endedCoupon.getEvent().getEventId();
        Long adminId = endedCoupon.getEvent().getCreatedBy().getUserId();

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
        entityManager.createNativeQuery("DELETE FROM event_status_history WHERE event_id = :eventId")
                .setParameter("eventId", eventId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM event WHERE event_id = :eventId")
                .setParameter("eventId", eventId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id = :userId")
                .setParameter("userId", adminId).executeUpdate();
    }

    @Test
    void 등록된_스케줄러가_ENDED_쿠폰을_자동으로_정합성_검증한다() {
        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        assertThat(reconciliationReportRepository
                                .findByCoupon_CouponIdOrderByAsOfAtDesc(
                                        endedCoupon.getCouponId(),
                                        org.springframework.data.domain.PageRequest.of(0, 1)))
                                .isNotEmpty()
                );
    }
}
