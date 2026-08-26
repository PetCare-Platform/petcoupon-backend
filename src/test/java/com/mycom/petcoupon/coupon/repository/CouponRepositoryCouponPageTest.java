package com.mycom.petcoupon.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

/**
 * findCouponPage는 Coupon과 CouponStock 사이에 연관관계가 없는 상태에서 엔티티 조인으로 두 테이블을
 * 묶는다. JPQL이 실제로 파싱·실행되는지는 Mock 테스트로 한 줄도 확인되지 않으므로 실 DB로 확인한다.
 * (CouponRepositoryDatabaseNowTest와 같은 이유의 통합 테스트)
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponRepositoryCouponPageTest {

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponStockRepository couponStockRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    private Long eventId;
    private Long otherEventId;

    @BeforeEach
    void setUp() {
        AppUser admin = appUserRepository.save(AppUser.builder()
                .name("관리자")
                .email("admin@petcoupon.test")
                .role(UserRole.ROLE_ADMIN)
                .build());

        Event event = eventRepository.save(event(admin, "여름 이벤트"));
        Event otherEvent = eventRepository.save(event(admin, "가을 이벤트"));
        eventId = event.getEventId();
        otherEventId = otherEvent.getEventId();

        saveCoupon(event, "여름 쿠폰 1", CouponStatus.READY, 100);
        saveCoupon(event, "여름 쿠폰 2", CouponStatus.ACTIVE, 200);
        saveCoupon(event, "여름 쿠폰 3", CouponStatus.ACTIVE, 300);
        saveCoupon(otherEvent, "가을 쿠폰", CouponStatus.READY, 400);
    }

    @Test
    void findCouponPageReturnsCouponWithEventAndStock() {
        Page<CouponWithStock> page = couponRepository.findCouponPage(eventId, null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);

        CouponWithStock newest = page.getContent().get(0);
        assertThat(newest.coupon().getName()).isEqualTo("여름 쿠폰 3");
        assertThat(newest.coupon().getEvent().getName()).isEqualTo("여름 이벤트");
        assertThat(newest.couponStock().getTotalQuantity()).isEqualTo(300);
        assertThat(newest.couponStock().getRemainingQuantity()).isEqualTo(300);
        assertThat(newest.couponStock().getIssuedQuantity()).isZero();
        // 재고 수치가 언제 기준인지 알려주는 값이라 비어 있으면 안 된다.
        assertThat(newest.couponStock().getUpdatedAt()).isNotNull();
    }

    // 정렬을 쿼리에 고정한 이유의 검증 — 페이지를 넘겨도 순서가 흔들리지 않아야 한다.
    @Test
    void findCouponPageOrdersByCouponIdDesc() {
        Page<CouponWithStock> firstPage = couponRepository.findCouponPage(eventId, null, PageRequest.of(0, 2));
        Page<CouponWithStock> secondPage = couponRepository.findCouponPage(eventId, null, PageRequest.of(1, 2));

        assertThat(names(firstPage)).containsExactly("여름 쿠폰 3", "여름 쿠폰 2");
        assertThat(names(secondPage)).containsExactly("여름 쿠폰 1");
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(secondPage.isLast()).isTrue();
    }

    @Test
    void findCouponPageFiltersByStatus() {
        Page<CouponWithStock> page = couponRepository.findCouponPage(
                eventId,
                CouponStatus.ACTIVE,
                PageRequest.of(0, 20)
        );

        assertThat(names(page)).containsExactly("여름 쿠폰 3", "여름 쿠폰 2");
    }

    @Test
    void findCouponPageWithoutEventFilterIncludesOtherEvents() {
        Page<CouponWithStock> page = couponRepository.findCouponPage(null, null, PageRequest.of(0, 4));

        // 다른 이벤트 쿠폰을 가장 마지막에 저장했으므로 couponId 내림차순에서 맨 앞에 온다.
        assertThat(page.getContent().get(0).coupon().getName()).isEqualTo("가을 쿠폰");
        assertThat(page.getContent().get(0).coupon().getEvent().getEventId()).isEqualTo(otherEventId);
    }

    // 목록에서 Redis를 쓰지 않기로 한 것과 같은 이유로, 재고·이벤트를 쿠폰 건수만큼 조회해서도 안 된다.
    // 한 페이지는 목록 쿼리 1번 + 카운트 쿼리 1번으로 끝나야 한다(첫 페이지에 전부 담기면
    // Spring Data가 카운트 쿼리를 생략하므로 1번이다). 재고나 이벤트가 fetch에서 빠지면
    // 쿠폰 건수만큼 늘어나서 이 상한을 넘는다.
    //
    // 통계는 프로퍼티가 아니라 여기서 켠다. @DataJpaTest에 properties를 주면 컨텍스트 캐시 키가
    // 달라져 이 클래스만 컨텍스트를 새로 띄우고, 그만큼 커넥션 풀이 하나 더 생겨
    // 전체 테스트를 돌릴 때 MySQL max_connections에 걸린다.
    @Test
    void findCouponPageDoesNotQueryPerCoupon() {
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        try {
            Page<CouponWithStock> page = couponRepository.findCouponPage(eventId, null, PageRequest.of(0, 20));
            page.getContent().forEach(row -> {
                row.coupon().getEvent().getName();
                row.couponStock().getTotalQuantity();
            });

            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2);
        } finally {
            // 컨텍스트를 다른 테스트와 공유하므로 원래 상태로 되돌려 놓는다.
            statistics.setStatisticsEnabled(false);
        }
    }

    private Event event(AppUser createdBy, String name) {
        return Event.builder()
                .createdBy(createdBy)
                .name(name)
                .description("테스트 이벤트")
                .openAt(LocalDateTime.of(2026, 8, 20, 0, 0))
                .closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
                .build();
    }

    private void saveCoupon(Event event, String name, CouponStatus status, int totalQuantity) {
        Coupon coupon = couponRepository.save(Coupon.builder()
                .event(event)
                .name(name)
                .discountType(DiscountType.RATE)
                .discountValue(20)
                .minOrderAmount(30_000)
                .maxDiscountAmount(10_000)
                .issueStartAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .issueEndAt(LocalDateTime.of(2026, 8, 30, 23, 59))
                .validDays(7)
                .build());

        couponStockRepository.save(CouponStock.builder()
                .coupon(coupon)
                .totalQuantity(totalQuantity)
                .build());

        // status는 스케줄러가 조건부 UPDATE로 바꾸는 값이라 엔티티에 변경 메서드가 없다.
        // 상태 필터를 검증하려면 같은 방식으로 직접 넣는 수밖에 없다.
        if (status != CouponStatus.READY) {
            entityManager.flush();
            entityManager.createQuery("update Coupon c set c.status = :status where c.couponId = :couponId")
                    .setParameter("status", status)
                    .setParameter("couponId", coupon.getCouponId())
                    .executeUpdate();
            entityManager.clear();
        }
    }

    private List<String> names(Page<CouponWithStock> page) {
        return page.getContent().stream()
                .map(row -> row.coupon().getName())
                .toList();
    }
}
