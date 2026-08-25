package com.mycom.petcoupon.coupon.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.coupon.entity.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

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
