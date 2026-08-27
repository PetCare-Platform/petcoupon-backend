package com.mycom.petcoupon.coupon.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;

import jakarta.persistence.LockModeType;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	// 관리자 목록 조회 전용. 한 페이지를 SELECT 한 번으로 끝내려고 이벤트는 fetch join,
	// 재고는 엔티티 조인으로 묶는다(연관관계가 없는 이유는 CouponWithStock 참고).
	// 재고를 따로 조회하면 20건 목록에 쿼리가 21번 나간다.
	//
	// eventId·status는 선택 필터라 null이면 조건 자체를 무력화시킨다.
	// 정렬을 Pageable에 맡기지 않고 쿼리에 고정한 건, 정렬 없이 페이징하면 페이지마다
	// 순서가 달라져 같은 쿠폰이 두 번 보이거나 빠질 수 있어서다. 최신순 + 식별자 tie-break는
	// 이벤트 목록(findAllByOrderByCreatedAtDescEventIdDesc)과 같은 기준으로 맞춘다.
	@Query(value = """
			SELECT new com.mycom.petcoupon.coupon.repository.CouponWithStock(c, cs)
			  FROM Coupon c
			  JOIN FETCH c.event e
			  JOIN CouponStock cs ON cs.coupon = c
			 WHERE (:eventId IS NULL OR e.eventId = :eventId)
			   AND (:status IS NULL OR c.status = :status)
			 ORDER BY c.createdAt DESC, c.couponId DESC
			""",
			countQuery = """
			SELECT count(c)
			  FROM Coupon c
			 WHERE (:eventId IS NULL OR c.event.eventId = :eventId)
			   AND (:status IS NULL OR c.status = :status)
			""")
	Page<CouponWithStock> findCouponPage(
			@Param("eventId") Long eventId,
			@Param("status") CouponStatus status,
			Pageable pageable
	);

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

	// ACTIVE -> SOLD_OUT 원자적 조건부 UPDATE. 판정 기준은 Redis가 아니라 coupon_stock
	// (Kafka 소비 후 확정된 remaining_quantity)이다 -- 발급 확정 이후에만 반영되므로 실시간
	// 소진 시점과는 스케줄러 주기(최대 60초)만큼 어긋날 수 있지만, 관리자 목록 필터 용도라 허용한다.
	// Coupon -> CouponStock 연관관계가 없어(CouponWithStock 참고) 서브쿼리로 묶는다.
	//
	// activateCoupons보다 뒤, endCoupons보다 앞에 실행해야 한다(CouponStatusSchedulerServiceImpl
	// 참고) -- 발급 기간이 끝났고 재고도 0인 쿠폰이 이번 실행에서 SOLD_OUT을 거쳐 곧장 ENDED로
	// 도달하게 하기 위함이다.
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE Coupon c
			   SET c.status = 'SOLD_OUT',
			       c.updatedAt = :now
			 WHERE c.status = 'ACTIVE'
			   AND c.couponId IN (
			       SELECT cs.couponId FROM CouponStock cs WHERE cs.remainingQuantity = 0
			   )
			""")
	int soldOutCoupons(@Param("now") LocalDateTime now);

	// ACTIVE/SOLD_OUT -> ENDED 원자적 조건부 UPDATE. SOLD_OUT도 대상에 포함하는 이유는, 품절을
	// 종착 상태로 두면 발급 기간이 끝난 지난 이벤트의 쿠폰이 계속 품절 목록에 남아 관리자 조회를
	// 오염시키기 때문이다 -- 발급 기간이 끝난 쿠폰은 품절이었든 아니든 종료로 수렴해야 한다.
	// status IN (...) 조건 덕분에 이미 READY이거나 이미 ENDED인 쿠폰은 건드리지 않는다.
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE Coupon c
			   SET c.status = 'ENDED',
			       c.updatedAt = :now
			 WHERE c.status IN ('ACTIVE', 'SOLD_OUT')
			   AND c.issueEndAt <= :now
			""")
	int endCoupons(@Param("now") LocalDateTime now);
}
