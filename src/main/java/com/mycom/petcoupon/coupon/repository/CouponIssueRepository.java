package com.mycom.petcoupon.coupon.repository;

import java.time.LocalDateTime;
import java.util.List;

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
	
	// 사용자별 발급 신청 내역 목록 조회용.
	// JOIN FETCH로 coupon을 같이 가져오는 이유: 안 그러면 LAZY 로딩이라 결과 리스트를 순회하며
	// couponIssue.getCoupon().getName()을 부를 때마다 쿼리가 또 나가는 N+1 문제가 생김.
	@Query("""
			SELECT ci FROM CouponIssue ci
			JOIN FETCH ci.coupon
			WHERE ci.user.userId = :userId
			ORDER BY ci.createdAt DESC
			""")
	List<CouponIssue> findAllByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
	


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