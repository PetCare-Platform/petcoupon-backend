package com.mycom.petcoupon.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * succeed()의 completeIfAllowed 조건부 UPDATE가 실제 동시 호출에서도 "최종 결과(200)가 항상
 * 이긴다"를 지키는지 확인한다. Consumer(200 최종 확정)와 HTTP 스레드(202 잠정 응답)가 정확히 같은
 * 순간에 같은 레코드를 갱신하도록 CyclicBarrier로 타이밍을 강제한다.
 *
 * 여러 스레드가 각자 커넥션을 잡고 실제로 커밋하므로 @DataJpaTest의 트랜잭션 롤백에 기댈 수 없어
 * @SpringBootTest + 수동 tearDown을 쓴다(ReconciliationJobTriggerServiceTest의
 * 동시_요청_두_개가_들어오면... 테스트와 같은 이유).
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@SpringBootTest(properties = {
        "event.status.scheduler.enabled=false",
        "coupon.status.enabled=false"
})
class IdempotencyKeyServiceImplSucceedConcurrencyIntegrationTest {

    @Autowired
    private IdempotencyKeyService idempotencyKeyService;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;
    private AppUser admin;
    private Event event;
    private Coupon coupon;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(platformTransactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            admin = appUserRepository.save(AppUser.builder()
                    .name("관리자")
                    .email("idem-concurrency-" + System.nanoTime() + "@test.com")
                    .role(UserRole.ROLE_ADMIN)
                    .build());

            event = eventRepository.save(Event.builder()
                    .createdBy(admin)
                    .name("멱등키 동시성 테스트 이벤트")
                    .description("설명")
                    .openAt(LocalDateTime.now().minusHours(1))
                    .closeAt(LocalDateTime.now().plusDays(1))
                    .build());

            coupon = couponRepository.save(Coupon.builder()
                    .event(event)
                    .name("멱등키 동시성 테스트 쿠폰")
                    .discountType(DiscountType.FIXED_AMOUNT)
                    .discountValue(1_000)
                    .minOrderAmount(10_000)
                    .issueStartAt(LocalDateTime.now().minusMinutes(10))
                    .issueEndAt(LocalDateTime.now().plusHours(1))
                    .validDays(7)
                    .build());
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM idempotency_key WHERE coupon_id = :couponId")
                    .setParameter("couponId", coupon.getCouponId())
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM coupon WHERE coupon_id = :couponId")
                    .setParameter("couponId", coupon.getCouponId())
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM event_status_history WHERE event_id = :eventId")
                    .setParameter("eventId", event.getEventId())
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM event WHERE event_id = :eventId")
                    .setParameter("eventId", event.getEventId())
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM app_user WHERE user_id = :userId")
                    .setParameter("userId", admin.getUserId())
                    .executeUpdate();
        });
    }

    // Consumer(200 최종 확정)와 HTTP 스레드(202 잠정 응답)가 정확히 같은 순간에 같은 레코드를
    // 갱신해도, DB commit 순서와 무관하게 최종 상태는 항상 200(SUCCEEDED)이어야 한다 — 200은
    // 조건 없이 항상 반영되고(completeIfAllowed의 provisional=false 분기), 202는 아직
    // IN_PROGRESS일 때만 반영된다. 202가 먼저 커밋돼도 뒤이은 200이 무조건 덮어써서 자기
    // 교정되므로, 매 라운드 새 레코드로 여러 번 반복해도 불변식이 깨지지 않아야 한다.
    @Test
    void succeed는_동시_호출에서도_200_최종_결과가_202_잠정_응답을_이긴다() throws Exception {
        int rounds = 20;

        for (int round = 0; round < rounds; round++) {
            int currentRound = round;
            Long recordId = transactionTemplate.execute(status ->
                    idempotencyKeyRepository.save(IdempotencyKey.builder()
                            .user(admin)
                            .coupon(coupon)
                            .idempotencyKey("concurrency-test-" + currentRound + "-" + System.nanoTime())
                            .requestHash("hash")
                            .expiresAt(LocalDateTime.now().plusSeconds(30))
                            .build()
                    ).getIdempotencyId()
            );

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            Future<?> consumerCall = executor.submit(() -> {
                barrier.await();
                idempotencyKeyService.succeed(recordId, 200, "{\"code\":\"200\"}");
                return null;
            });
            Future<?> httpThreadCall = executor.submit(() -> {
                barrier.await();
                idempotencyKeyService.succeed(recordId, 202, "{\"code\":\"202\",\"status\":\"WAITING\"}");
                return null;
            });

            consumerCall.get();
            httpThreadCall.get();
            executor.shutdown();

            entityManager.clear();
            IdempotencyKey result = idempotencyKeyRepository.findById(recordId).orElseThrow();
            assertThat(result.getStatus())
                    .as("round=%d", round)
                    .isEqualTo(IdempotencyStatus.SUCCEEDED);
            assertThat(result.getResponseStatus()).as("round=%d", round).isEqualTo(200);
            assertThat(result.getResponseBody()).as("round=%d", round).isEqualTo("{\"code\": \"200\"}");
        }
    }
}
