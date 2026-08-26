package com.mycom.petcoupon.coupon.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.coupon.entity.Coupon;

import jakarta.persistence.LockModeType;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	// 관리자 수정 API 전용. 더티체킹 UPDATE는 status를 포함한 전체 컬럼을 쓰기 때문에,
	// 락 없이 읽으면 그 사이 activateCoupons가 만든 ACTIVE를 낡은 READY로 되돌려버린다.
	// 행을 잠가서 스케줄러와 직렬화시킨다(락 순서는 coupon -> coupon_stock으로 발급 경로와 동일).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.couponId = :couponId")
	Optional<Coupon> findByIdForUpdate(@Param("couponId") Long couponId);

	// 발급 시작 여부 판정 기준 시각. 애플리케이션 시계를 쓰면 인스턴스마다 판단이 갈리고
	// DB와 드리프트가 나므로, 모두가 공유하는 DB 시계를 기준으로 삼는다.
	@Query(value = "SELECT CURRENT_TIMESTAMP(6)", nativeQuery = true)
	LocalDateTime findDatabaseNow();

	// READY -> ACTIVE 원자적 조건부 UPDATE. issueEndAt > now 조건 덕분에, 시작·종료 시각이
	// 둘 다 이미 지난 쿠폰(스케줄러가 한동안 안 돌았던 경우 등)은 여기서 자동 전이시키지 않고
	// READY로 그대로 둔다 — 관리자가 확인해야 할 비정상 케이스라서 ACTIVE를 거치지 않고
	// 곧장 ENDED로 넘기지 않기 위함.
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE Coupon c
			   SET c.status = 'ACTIVE',
			       c.updatedAt = :now
			 WHERE c.status = 'READY'
			   AND c.issueStartAt <= :now
			   AND c.issueEndAt > :now
			""")
	int activateCoupons(@Param("now") LocalDateTime now);

	// ACTIVE -> ENDED 원자적 조건부 UPDATE. status='ACTIVE' 조건 덕분에 이미 SOLD_OUT 등
	// 다른 상태로 바뀐 쿠폰은 건드리지 않는다.
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE Coupon c
			   SET c.status = 'ENDED',
			       c.updatedAt = :now
			 WHERE c.status = 'ACTIVE'
			   AND c.issueEndAt <= :now
			""")
	int endCoupons(@Param("now") LocalDateTime now);
}
