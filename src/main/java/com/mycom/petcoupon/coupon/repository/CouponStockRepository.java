package com.mycom.petcoupon.coupon.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.coupon.entity.CouponStock;

import jakarta.persistence.LockModeType;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

	// 관리자 수정 API 전용. issuedQuantity=0 검사와 총수량 갱신 사이에 발급이 끼어들면
	// 더티체킹 UPDATE가 낡은 issued_quantity=0으로 발급 기록을 덮어써 초과발급이 된다.
	// 행을 잠가서 increaseIssuedQuantity와 직렬화시킨다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select cs from CouponStock cs where cs.couponId = :couponId")
	Optional<CouponStock> findByIdForUpdate(@Param("couponId") Long couponId);

	// Redis에서 이미 재고 판정이 끝난 뒤(비동기 DB 확정 단계)의 갱신이라 조건부 UPDATE로 충분히 안전함.
	//
	// updatedAt을 CURRENT_TIMESTAMP로 함께 갱신한다 -- 이 메서드는 @Modifying 벌크 UPDATE라
	// 영속성 컨텍스트를 거치지 않아 @LastModifiedDate가 개입하지 않는다. 그래서 수량이 바뀌어도
	// updated_at은 쿠폰 생성 또는 총수량 수정 시각에 머물러 있었다(관리자 쿠폰 목록의
	// stockUpdatedAt 응답 필드가 이 값을 기준 시각으로 쓰려다 실제와 어긋나 한동안 응답에서
	// 뺐던 이유, 자세한 배경은 이슈 #146 참고). 파라미터 대신 DB 함수를 쓰는 이유는 이 메서드를
	// 호출하는 CouponIssuePersister의 시그니처를 건드리지 않기 위해서다.
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE CouponStock cs
			   SET cs.issuedQuantity = cs.issuedQuantity + 1,
			       cs.remainingQuantity = cs.remainingQuantity - 1,
			       cs.updatedAt = CURRENT_TIMESTAMP
			 WHERE cs.couponId = :couponId
			   AND cs.remainingQuantity > 0
			""")
	int increaseIssuedQuantity(@Param("couponId") Long couponId);
}
