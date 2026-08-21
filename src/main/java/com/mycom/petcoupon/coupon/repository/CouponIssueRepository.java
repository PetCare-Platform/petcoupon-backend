package com.mycom.petcoupon.coupon.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;

import jakarta.persistence.LockModeType;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

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
	        @Param("now") LocalDateTime now
	);

	@Modifying(clearAutomatically = true)
	@Query("""
	        UPDATE CouponIssue c
	           SET c.status = :toStatus
	         WHERE c.couponIssueId IN :ids
	        """)
	int expireByIds(
	        @Param("ids") List<Long> ids,
	        @Param("toStatus") IssueStatus toStatus
	);
}