package com.mycom.petcoupon.idempotency.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 실패 사유 분류 집계(#195)용 countRejectionsByCouponId 검증. response_body에서
 * JSON_EXTRACT로 code를 뽑아내는 네이티브 쿼리라 실제 MySQL의 JSON 함수 동작을 봐야 한다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdempotencyKeyRepositoryTest {

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private IdempotencyKeyRepository idempotencyKeyRepository;

	private Coupon coupon;
	private AppUser user;

	@BeforeEach
	void setUp() {
		user = AppUser.builder()
				.name("IdempotencyKeyRepository 테스트 사용자")
				.email("idem-repo-test-" + System.nanoTime() + "@test.com")
				.phone("010-1234-5678")
				.build();
		entityManager.persist(user);

		LocalDateTime now = LocalDateTime.now();

		Event event = Event.builder()
				.createdBy(user)
				.name("IdempotencyKeyRepository 테스트 이벤트")
				.description("설명")
				.openAt(now.minusHours(1))
				.closeAt(now.plusDays(1))
				.build();
		entityManager.persist(event);

		coupon = Coupon.builder()
				.event(event)
				.name("IdempotencyKeyRepository 테스트 쿠폰")
				.discountType(DiscountType.values()[0])
				.discountValue(1_000)
				.minOrderAmount(10_000)
				.maxDiscountAmount(null)
				.issueStartAt(now.minusMinutes(10))
				.issueEndAt(now.plusHours(1))
				.validDays(7)
				.build();
		entityManager.persist(coupon);
	}

	private IdempotencyKey failedKey(String key, CouponErrorCode errorCode) {
		IdempotencyKey idempotencyKey = IdempotencyKey.builder()
				.user(user)
				.coupon(coupon)
				.idempotencyKey(key)
				.requestHash("hash-" + key)
				.expiresAt(LocalDateTime.now().plusMinutes(5))
				.build();
		entityManager.persist(idempotencyKey);
		idempotencyKey.complete(
				IdempotencyStatus.FAILED,
				errorCode.getStatus().value(),
				"{\"isSuccess\":false,\"code\":\"" + errorCode.getCode() + "\",\"message\":\"" + errorCode.getMessage() + "\"}"
		);
		return idempotencyKey;
	}

	@Test
	void 쿠폰별_SOLD_OUT과_ALREADY_ISSUED_건수를_따로_센다() {
		failedKey("sold-out-1", CouponErrorCode.SOLD_OUT);
		failedKey("sold-out-2", CouponErrorCode.SOLD_OUT);
		failedKey("dup-1", CouponErrorCode.DUPLICATE_USER);
		// 다른 코드(집계 대상 아님)와 성공 건이 섞여 있어도 영향이 없어야 한다
		failedKey("in-progress-conflict", CouponErrorCode.REQUEST_IN_PROGRESS);

		entityManager.flush();
		entityManager.clear();

		IdempotencyRejectionCounts counts = idempotencyKeyRepository.countRejectionsByCouponId(
				coupon.getCouponId(), CouponErrorCode.SOLD_OUT.getCode(), CouponErrorCode.DUPLICATE_USER.getCode()
		);

		assertThat(counts.getSoldOut()).isEqualTo(2);
		assertThat(counts.getAlreadyIssued()).isEqualTo(1);
	}

	@Test
	void 실패_건이_없으면_0을_반환한다() {
		entityManager.flush();
		entityManager.clear();

		IdempotencyRejectionCounts counts = idempotencyKeyRepository.countRejectionsByCouponId(
				coupon.getCouponId(), CouponErrorCode.SOLD_OUT.getCode(), CouponErrorCode.DUPLICATE_USER.getCode()
		);

		assertThat(counts.getSoldOut()).isZero();
		assertThat(counts.getAlreadyIssued()).isZero();
	}
}
