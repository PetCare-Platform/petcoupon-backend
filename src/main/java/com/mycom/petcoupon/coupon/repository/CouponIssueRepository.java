package com.mycom.petcoupon.coupon.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;

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
}