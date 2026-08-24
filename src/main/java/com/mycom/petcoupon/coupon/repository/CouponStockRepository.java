package com.mycom.petcoupon.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.coupon.entity.CouponStock;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

	// Redis에서 이미 재고 판정이 끝난 뒤(비동기 DB 확정 단계)의 갱신이라 조건부 UPDATE로 충분히 안전함
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE CouponStock cs
			   SET cs.issuedQuantity = cs.issuedQuantity + 1,
			       cs.remainingQuantity = cs.remainingQuantity - 1
			 WHERE cs.couponId = :couponId
			   AND cs.remainingQuantity > 0
			""")
	int increaseIssuedQuantity(@Param("couponId") Long couponId);
}
