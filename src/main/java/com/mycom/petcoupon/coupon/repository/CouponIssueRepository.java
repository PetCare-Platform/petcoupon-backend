package com.mycom.petcoupon.coupon.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;

import jakarta.persistence.LockModeType;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	// Kafka Consumer 중복 소비 방지용 (request_id unique 제약과 짝을 이룸)
	boolean existsByRequestId(String requestId);

	@Modifying(clearAutomatically = true)
    @Query("""
            UPDATE CouponIssue c
               SET c.status = :toStatus, c.usedAt = :usedAt
             WHERE c.couponIssueId = :couponIssueId
               AND c.status = :fromStatus
               AND c.expiresAt > :usedAt
            """)
    int updateStatusIfMatches(
            @Param("couponIssueId") Long couponIssueId,
            @Param("fromStatus") IssueStatus fromStatus,
            @Param("toStatus") IssueStatus toStatus,
            @Param("usedAt") LocalDateTime usedAt
    );
	
	// 사용자별 발급 신청 내역 목록 조회용. status가 null이면 전체, 값이 있으면 해당 상태만 필터링.
	// JOIN FETCH로 coupon을 같이 가져오는 이유: 안 그러면 LAZY 로딩이라 결과 리스트를 순회하며
	// couponIssue.getCoupon().getName()을 부를 때마다 쿼리가 또 나가는 N+1 문제가 생김.
	@Query("""
			SELECT ci FROM CouponIssue ci
			JOIN FETCH ci.coupon
			WHERE ci.user.userId = :userId
			  AND (:status IS NULL OR ci.status = :status)
			ORDER BY ci.createdAt DESC
			""")
	List<CouponIssue> findAllByUserIdAndStatusOrderByCreatedAtDesc(
			@Param("userId") Long userId,
			@Param("status") IssueStatus status
	);
	


	@Modifying(clearAutomatically = true)
	@Query("""
	        UPDATE CouponIssue c
	           SET c.status = :toStatus, c.usedAt = NULL
	         WHERE c.couponIssueId = :couponIssueId
	           AND c.status = :fromStatus
	           AND c.expiresAt > :now
	        """)
	int cancelUsageIfMatches(
	        @Param("couponIssueId") Long couponIssueId,
	        @Param("fromStatus") IssueStatus fromStatus,
	        @Param("toStatus") IssueStatus toStatus,
	        @Param("now") LocalDateTime now
	);
	
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
	        SELECT c.couponIssueId FROM CouponIssue c
	         WHERE c.status = :status
	           AND c.expiresAt < :now
	        """)
	List<Long> findIdsToExpire(
	        @Param("status") IssueStatus status,
	        @Param("now") LocalDateTime now,
	        Pageable pageable
	);

	@Modifying(clearAutomatically = true)
	@Query("""
	        UPDATE CouponIssue c
	           SET c.status = :toStatus
	         WHERE c.couponIssueId IN :ids
	           AND c.status = :fromStatus
	        """)
	int expireByIds(
	        @Param("ids") List<Long> ids,
	        @Param("fromStatus") IssueStatus fromStatus,
	        @Param("toStatus") IssueStatus toStatus
	);
}