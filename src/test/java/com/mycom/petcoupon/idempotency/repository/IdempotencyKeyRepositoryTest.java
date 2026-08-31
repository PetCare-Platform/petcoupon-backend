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

	private IdempotencyKey newKey(String key) {
		IdempotencyKey idempotencyKey = IdempotencyKey.builder()
				.user(user)
				.coupon(coupon)
				.idempotencyKey(key)
				.requestHash("hash-" + key)
				.expiresAt(LocalDateTime.now().plusMinutes(5))
				.build();
		entityManager.persist(idempotencyKey);
		return idempotencyKey;
	}

	private IdempotencyKey failedKey(String key, CouponErrorCode errorCode) {
		IdempotencyKey idempotencyKey = newKey(key);
		idempotencyKey.complete(
				IdempotencyStatus.FAILED,
				errorCode.getStatus().value(),
				"{\"isSuccess\":false,\"code\":\"" + errorCode.getCode() + "\",\"message\":\"" + errorCode.getMessage() + "\"}"
		);
		return idempotencyKey;
	}

	// CouponController가 Redis 등 인프라 예외로 끊겼을 때(IdempotencyKeyServiceImpl.failWithoutBody)
	// 만드는 상태 — 202조차 못 받은 것과 동일하게 accepted/rejected 어느 쪽에도 안 잡혀야 한다.
	private IdempotencyKey failedWithoutBodyKey(String key) {
		IdempotencyKey idempotencyKey = newKey(key);
		idempotencyKey.complete(IdempotencyStatus.FAILED, null, null);
		return idempotencyKey;
	}

	// 202 잠정 응답까지만 받고 아직 최종 확정(Consumer)은 안 된 상태.
	private IdempotencyKey succeededKey(String key) {
		IdempotencyKey idempotencyKey = newKey(key);
		idempotencyKey.complete(IdempotencyStatus.SUCCEEDED, 202, "{\"isSuccess\":true}");
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

	// [PR #195 리뷰 반영] begin()이 Stream 발행보다 먼저 IN_PROGRESS 행을 만들기 때문에,
	// 부하 도중 폴링하면 아직 202조차 못 받은 IN_PROGRESS 건이 섞여 있을 수 있다.
	// 이 건은 accepted에 잡히면 안 된다.
	@Test
	void accepted_count는_아직_응답을_못받은_IN_PROGRESS_건을_제외한다() {
		newKey("still-in-progress");
		succeededKey("got-202");

		entityManager.flush();
		entityManager.clear();

		long accepted = idempotencyKeyRepository.countAcceptedByCouponId(coupon.getCouponId());

		assertThat(accepted).isEqualTo(1);
	}

	// 응답 자체가 안 남은 인프라 예외(failWithoutBody)도 IN_PROGRESS와 같은 이유로 제외돼야 한다 —
	// 재시도가 이 레코드를 이어받을 예정이라 "접수됐다"고 보기엔 이르다.
	@Test
	void accepted_count는_무응답_실패_건도_제외한다() {
		// 세 건 다 같은 유저다 — DISTINCT user_id 기준이라 응답 있는 건(succeeded, sold-out)이
		// 있는 한 무응답 실패 건이 섞여도 결과가 1을 넘지 않는다. "무응답 실패 건만 있으면 0건"과
		// 대비하려면 별도 유저가 필요하므로, 그건 다음 테스트에서 확인한다.
		failedWithoutBodyKey("no-body-failure");
		succeededKey("got-202");
		failedKey("sold-out", CouponErrorCode.SOLD_OUT);

		entityManager.flush();
		entityManager.clear();

		long accepted = idempotencyKeyRepository.countAcceptedByCouponId(coupon.getCouponId());

		assertThat(accepted).isEqualTo(1);
	}

	@Test
	void accepted_count는_무응답_실패만_있는_유저는_전혀_세지_않는다() {
		AppUser onlyFailedWithoutBodyUser = AppUser.builder()
				.name("무응답 실패 전용 사용자")
				.email("no-body-only-" + System.nanoTime() + "@test.com")
				.phone("010-1234-5678")
				.build();
		entityManager.persist(onlyFailedWithoutBodyUser);

		IdempotencyKey key = IdempotencyKey.builder()
				.user(onlyFailedWithoutBodyUser)
				.coupon(coupon)
				.idempotencyKey("no-body-only")
				.requestHash("hash-no-body-only")
				.expiresAt(LocalDateTime.now().plusMinutes(5))
				.build();
		entityManager.persist(key);
		key.complete(IdempotencyStatus.FAILED, null, null);

		entityManager.flush();
		entityManager.clear();

		long accepted = idempotencyKeyRepository.countAcceptedByCouponId(coupon.getCouponId());

		assertThat(accepted).isZero();
	}

	// [PR #195 리뷰 반영] rejected를 accepted - passed로 빼는 대신 최종 FAILED 확정 건을
	// 직접 센다 — IN_PROGRESS나 무응답 실패는 rejected에도 안 잡혀야 한다.
	@Test
	void rejected_count는_최종_FAILED_확정_건만_센다() {
		newKey("still-in-progress");
		failedWithoutBodyKey("no-body-failure");
		succeededKey("got-202");
		failedKey("sold-out-1", CouponErrorCode.SOLD_OUT);
		failedKey("sold-out-2", CouponErrorCode.SOLD_OUT);

		entityManager.flush();
		entityManager.clear();

		long rejected = idempotencyKeyRepository.countByCoupon_CouponIdAndStatusAndResponseStatusIsNotNull(
				coupon.getCouponId(), IdempotencyStatus.FAILED
		);

		assertThat(rejected).isEqualTo(2);
	}
}
