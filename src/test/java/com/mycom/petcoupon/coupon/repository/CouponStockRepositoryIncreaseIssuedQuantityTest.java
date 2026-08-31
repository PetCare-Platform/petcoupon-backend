package com.mycom.petcoupon.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * increaseIssuedQuantity는 @Modifying 벌크 UPDATE라 영속성 컨텍스트를 거치지 않아
 * @LastModifiedDate가 개입하지 않는다. updated_at을 CURRENT_TIMESTAMP로 직접 갱신하는 게
 * 실제로 반영되는지는 Mock 테스트로 한 줄도 확인되지 않으므로 실 DB로 확인한다.
 * (CouponRepositoryCouponPageTest와 같은 이유의 통합 테스트)
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponStockRepositoryIncreaseIssuedQuantityTest {

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponStockRepository couponStockRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Long couponId;

    @BeforeEach
    void setUp() {
        AppUser admin = appUserRepository.save(AppUser.builder()
                .name("관리자")
                .email("stock-updated-at@petcoupon.test")
                .role(UserRole.ROLE_ADMIN)
                .build());

        Event event = eventRepository.save(Event.builder()
                .createdBy(admin)
                .name("재고 갱신 시각 테스트 이벤트")
                .description("테스트 이벤트")
                .openAt(LocalDateTime.of(2026, 8, 20, 0, 0))
                .closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
                .build());

        Coupon coupon = couponRepository.save(Coupon.builder()
                .event(event)
                .name("재고 갱신 시각 테스트 쿠폰")
                .discountType(DiscountType.RATE)
                .discountValue(10)
                .minOrderAmount(10_000)
                .issueStartAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .issueEndAt(LocalDateTime.of(2026, 8, 30, 23, 59))
                .validDays(7)
                .build());
        couponId = coupon.getCouponId();
    }

    // CURRENT_TIMESTAMP는 초 단위로 잘리므로(CouponRepositoryDatabaseNowTest와 같은 이유),
    // 생성 시각과 발급 확정 시각이 같은 초 안에 들어가면 갱신 전후를 구분하지 못한다.
    // 초 경계를 넘기고 나서 확정하도록 짧게 대기한다.
    @Test
    void increaseIssuedQuantityUpdatesUpdatedAtWhenStockIsAvailable() throws InterruptedException {
        CouponStock stock = couponStockRepository.save(CouponStock.builder()
                .coupon(couponRepository.findById(couponId).orElseThrow())
                .totalQuantity(2)
                .build());
        LocalDateTime createdAt = stock.getUpdatedAt();

        Thread.sleep(1100);

        int updatedRows = couponStockRepository.increaseIssuedQuantity(couponId);
        entityManager.clear();

        CouponStock afterIssue = couponStockRepository.findById(couponId).orElseThrow();
        assertThat(updatedRows).isEqualTo(1);
        assertThat(afterIssue.getIssuedQuantity()).isEqualTo(1);
        assertThat(afterIssue.getRemainingQuantity()).isEqualTo(1);
        // 발급 확정 전에는 쿠폰 생성 시각에 머물러 있던 값이, 확정 후에는 그 시각을 지나 있어야 한다.
        assertThat(afterIssue.getUpdatedAt()).isAfter(createdAt);
    }

    // WHERE remainingQuantity > 0 조건에 걸려 갱신 자체가 일어나지 않는 경우다.
    // 이때는 issuedQuantity·remainingQuantity와 마찬가지로 updatedAt도 그대로여야 한다 --
    // 실패한 시도가 재고 갱신 시각을 흔들면 그 시각의 의미(마지막으로 실제 반영된 시점)가 깨진다.
    @Test
    void increaseIssuedQuantityDoesNotChangeUpdatedAtWhenStockIsExhausted() throws InterruptedException {
        couponStockRepository.save(CouponStock.builder()
                .coupon(couponRepository.findById(couponId).orElseThrow())
                .totalQuantity(1)
                .build());

        couponStockRepository.increaseIssuedQuantity(couponId);
        entityManager.clear();
        LocalDateTime updatedAtAfterExhausted = couponStockRepository.findById(couponId).orElseThrow().getUpdatedAt();

        Thread.sleep(1100);

        int updatedRows = couponStockRepository.increaseIssuedQuantity(couponId);
        entityManager.clear();

        CouponStock afterSecondAttempt = couponStockRepository.findById(couponId).orElseThrow();
        assertThat(updatedRows).isZero();
        assertThat(afterSecondAttempt.getIssuedQuantity()).isEqualTo(1);
        assertThat(afterSecondAttempt.getRemainingQuantity()).isZero();
        assertThat(afterSecondAttempt.getUpdatedAt()).isEqualTo(updatedAtAfterExhausted);
    }
}
