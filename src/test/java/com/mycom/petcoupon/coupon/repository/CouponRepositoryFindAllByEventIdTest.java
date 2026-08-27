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

import com.mycom.petcoupon.coupon.entity.Coupon;
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
 * findAllByEventId는 공개 이벤트 상세(GET /events/{eventId})가 쿠폰 기본정보를 읽는 통로다.
 * JPQL의 실제 파싱·실행과 "쿠폰 수와 무관하게 SELECT 1번"은 Mock으로 확인되지 않으므로 실 DB로 본다.
 * (CouponRepositoryCouponPageTest와 같은 이유의 통합 테스트)
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponRepositoryFindAllByEventIdTest {

	@Autowired
	private CouponRepository couponRepository;

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
	private Long emptyEventId;

	@BeforeEach
	void setUp() {
		AppUser admin = appUserRepository.save(AppUser.builder()
				.name("관리자")
				.email("admin@petcoupon.test")
				.role(UserRole.ROLE_ADMIN)
				.build());

		Event event = eventRepository.save(event(admin, "여름 이벤트"));
		Event otherEvent = eventRepository.save(event(admin, "가을 이벤트"));
		Event emptyEvent = eventRepository.save(event(admin, "쿠폰 없는 이벤트"));
		eventId = event.getEventId();
		otherEventId = otherEvent.getEventId();
		emptyEventId = emptyEvent.getEventId();

		saveCoupon(event, "여름 쿠폰 1");
		saveCoupon(event, "여름 쿠폰 2");
		saveCoupon(event, "여름 쿠폰 3");
		saveCoupon(otherEvent, "가을 쿠폰");
	}

	@Test
	void returnsOnlyCouponsOfGivenEventOrderedByNewest() {
		List<Coupon> coupons = couponRepository.findAllByEventId(eventId);

		assertThat(coupons).extracting(Coupon::getName)
				.containsExactly("여름 쿠폰 3", "여름 쿠폰 2", "여름 쿠폰 1");
		assertThat(coupons).allSatisfy(coupon ->
				assertThat(coupon.getEvent().getEventId()).isEqualTo(eventId));
	}

	@Test
	void returnsEmptyListWhenEventHasNoCoupon() {
		assertThat(couponRepository.findAllByEventId(emptyEventId)).isEmpty();
	}

	@Test
	void doesNotIncludeCouponsOfOtherEvents() {
		assertThat(couponRepository.findAllByEventId(otherEventId))
				.extracting(Coupon::getName)
				.containsExactly("가을 쿠폰");
	}

	// 공개 상세 응답(EventCouponResponse)은 이벤트 필드를 쓰지 않으므로, 쿠폰이 몇 건이든
	// 목록 조회는 SELECT 1번으로 끝나야 한다. event를 건드려 지연 로딩이 걸리면 건수만큼 늘어난다.
	// 통계 on/off를 프로퍼티가 아니라 여기서 하는 이유는 CouponRepositoryCouponPageTest 참고.
	@Test
	void doesNotQueryPerCoupon() {
		entityManager.flush();
		entityManager.clear();

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		try {
			List<Coupon> coupons = couponRepository.findAllByEventId(eventId);
			coupons.forEach(coupon -> {
				coupon.getName();
				coupon.getStatus();
			});

			assertThat(coupons).hasSize(3);
			assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		} finally {
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

	private void saveCoupon(Event event, String name) {
		couponRepository.save(Coupon.builder()
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
	}
}
