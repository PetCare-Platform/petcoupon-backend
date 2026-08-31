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

	// 대시보드 요약 집계(#172)용 — 전체 쿠폰 재고 대비 발급률(issuedQuantity/totalQuantity)을
	// 계산하려고 전체 합계 3개를 한 번에 구한다.
	//
	// [PR 리뷰 반영] READY(발급 시작 전) 쿠폰은 뺀다 — READY는 issuedQuantity가 항상 0인데
	// totalQuantity는 분모에 그대로 들어가서, READY 쿠폰을 많이 만들어둘수록 실제 수요와
	// 무관하게 전체 발급률이 낮아 보이게 된다. ACTIVE(발급 중)·SOLD_OUT(품절)·ENDED(종료)는
	// 전부 발급이 실제로 시작된 적 있는 쿠폰이라 포함한다 — ENDED까지 포함하는 건 누적
	// 실적으로 의미가 있어서다. activeCoupons(CouponRepository.countByStatus(ACTIVE))와
	// 기준을 맞추는 목적도 있다 — 한쪽은 "진행중만", 다른 쪽은 "READY 포함 전부"면 같은
	// 응답 안에서 기준이 안 맞아 헷갈린다.
	//
	// coupon_stock이 비어 있으면(대상 쿠폰이 하나도 없으면) SUM이 NULL을 반환하므로 COALESCE로
	// 0을 기본값으로 깐다 — 안 그러면 서비스가 매번 null 체크를 해야 한다. SUM(int)는 JPQL
	// 스펙상 항상 Long으로 승격되므로(네이티브 쿼리처럼 DB 드라이버에 따라 DECIMAL로 나올
	// 위험이 없다) 별도 검증 없이 Long 매핑을 신뢰할 수 있다.
	@Query("""
			SELECT COALESCE(SUM(cs.totalQuantity), 0) AS totalQuantity,
			       COALESCE(SUM(cs.issuedQuantity), 0) AS issuedQuantity,
			       COALESCE(SUM(cs.remainingQuantity), 0) AS remainingQuantity
			  FROM CouponStock cs
			 WHERE cs.coupon.status IN ('ACTIVE', 'SOLD_OUT', 'ENDED')
			""")
	CouponStockSummary sumStock();
}
