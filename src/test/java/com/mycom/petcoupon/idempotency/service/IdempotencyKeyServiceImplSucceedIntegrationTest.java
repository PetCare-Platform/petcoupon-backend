package com.mycom.petcoupon.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

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
 * succeed()는 이제 findById+엔티티 조작이 아니라 조건부 UPDATE(IdempotencyKeyRepository.
 * completeIfAllowed) 한 번으로 끝난다 — Mockito로는 그 WHERE 조건(202류 잠정 쓰기는 IN_PROGRESS일
 * 때만 반영)이 실제로 지켜지는지 증명할 수 없어서, 여기서 실 DB로 확인한다.
 * (CouponStockRepositoryIncreaseIssuedQuantityTest와 같은 이유의 통합 테스트)
 *
 * 순차 호출 시나리오만 다룬다 — HTTP 스레드/Consumer가 실제로 동시에 호출될 때의 레이스 재현은
 * 별도의 동시성 테스트가 다룬다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IdempotencyKeyServiceImpl.class, IdempotencyKeyCreator.class})
class IdempotencyKeyServiceImplSucceedIntegrationTest {

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

    @PersistenceContext
    private EntityManager entityManager;

    private Long recordId;

    @BeforeEach
    void setUp() {
        AppUser admin = appUserRepository.save(AppUser.builder()
                .name("관리자")
                .email("idem-succeed-" + System.nanoTime() + "@test.com")
                .role(UserRole.ROLE_ADMIN)
                .build());

        Event event = eventRepository.save(Event.builder()
                .createdBy(admin)
                .name("멱등키 succeed 테스트 이벤트")
                .description("설명")
                .openAt(LocalDateTime.now().minusHours(1))
                .closeAt(LocalDateTime.now().plusDays(1))
                .build());

        Coupon coupon = couponRepository.save(Coupon.builder()
                .event(event)
                .name("멱등키 succeed 테스트 쿠폰")
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1_000)
                .minOrderAmount(10_000)
                .issueStartAt(LocalDateTime.now().minusMinutes(10))
                .issueEndAt(LocalDateTime.now().plusHours(1))
                .validDays(7)
                .build());

        IdempotencyKey record = idempotencyKeyRepository.save(IdempotencyKey.builder()
                .user(admin)
                .coupon(coupon)
                .idempotencyKey("succeed-test-" + System.nanoTime())
                .requestHash("hash")
                .expiresAt(LocalDateTime.now().plusSeconds(30))
                .build());
        recordId = record.getIdempotencyId();
    }

    @Test
    void succeed_정상_호출시_상태와_응답을_SUCCEEDED로_완료한다() {
        idempotencyKeyService.succeed(recordId, 200, "{\"isSuccess\":true}");

        // response_body는 JSON 컬럼이라 MySQL이 저장 시 콜론 뒤에 공백을 넣는 등 정규화한다 —
        // 입력 그대로가 아니라 MySQL이 실제로 돌려주는 정규화된 형태와 비교한다.
        entityManager.clear();
        IdempotencyKey updated = idempotencyKeyRepository.findById(recordId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(updated.getResponseStatus()).isEqualTo(200);
        assertThat(updated.getResponseBody()).isEqualTo("{\"isSuccess\": true}");
    }

    @Test
    void succeed_이미_200_OK로_완료된_레코드에_뒤늦은_202_접수응답이_오면_덮어쓰지_않는다() {
        idempotencyKeyService.succeed(recordId, 200, "{\"code\":\"200\"}");
        entityManager.clear();

        idempotencyKeyService.succeed(recordId, 202, "{\"code\":\"202\",\"status\":\"WAITING\"}");

        // 200 응답이 유지되어야 함 (JSON 컬럼 정규화로 콜론 뒤 공백이 붙음)
        entityManager.clear();
        IdempotencyKey updated = idempotencyKeyRepository.findById(recordId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(IdempotencyStatus.SUCCEEDED);
        assertThat(updated.getResponseStatus()).isEqualTo(200);
        assertThat(updated.getResponseBody()).isEqualTo("{\"code\": \"200\"}");
    }

    @Test
    void succeed_이미_FAILED로_완료된_레코드에_뒤늦은_202_접수응답이_오면_덮어쓰지_않는다() {
        // fail()도 findById+complete() 방식이지만, 이 테스트가 보려는 건 succeed()의 방어 로직이지
        // fail() 자체가 아니라서 여기선 엔티티를 직접 FAILED로 만들어둔다.
        // saveAndFlush여야 한다 — succeed()가 실제로 부르는 completeIfAllowed()는 벌크 JPQL UPDATE라
        // 영속성 컨텍스트를 거치지 않고 DB를 직접 본다. save()만 하면 이 FAILED 변경이 아직 플러시
        // 전이라, 뒤이은 벌크 UPDATE가 DB에 남아있는 낡은(IN_PROGRESS) 값을 보고 조건을 통과해버린다.
        IdempotencyKey record = idempotencyKeyRepository.findById(recordId).orElseThrow();
        record.complete(IdempotencyStatus.FAILED, 409, "{\"code\":\"409\"}");
        idempotencyKeyRepository.saveAndFlush(record);
        entityManager.clear();

        idempotencyKeyService.succeed(recordId, 202, "{\"code\":\"202\",\"status\":\"WAITING\"}");

        // FAILED 상태와 409 응답이 유지되어야 함 (JSON 컬럼 정규화로 콜론 뒤 공백이 붙음)
        entityManager.clear();
        IdempotencyKey updated = idempotencyKeyRepository.findById(recordId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(updated.getResponseStatus()).isEqualTo(409);
        assertThat(updated.getResponseBody()).isEqualTo("{\"code\": \"409\"}");
    }
}
