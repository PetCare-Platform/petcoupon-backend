package com.mycom.petcoupon.coupon.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.coupon.entity.enums.HistoryActorType;
import com.mycom.petcoupon.coupon.entity.enums.IssueHistoryStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter 
// 정합성 검증 배치가 coupon_id로 이력을 조회하는데, 인덱스가 없으면 history_id 순으로
// 테이블 전체를 훑는다(EXPLAIN으로 확인함) — coupon_id로 좁히면서 history_id 정렬도
// 같이 해결되는 복합 인덱스.
@Table(
	name = "coupon_issue_history",
	indexes = @Index(name = "idx_history_coupon_id", columnList = "coupon_id, history_id")
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)  
public class CouponIssueHistory {
	
	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "history_id") 
	private Long historyId;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false) 
	@JoinColumn(name = "coupon_issue_id") 
	private CouponIssue couponIssue;
	
	@Column(name = "coupon_id", nullable = false) 
	private long couponId; 
	
	@Column(name = "user_id", nullable = false) 
	private long userId;
	
	@Enumerated(EnumType.STRING) 
	@Column(name = "from_status", nullable = false) 
	private IssueHistoryStatus fromStatus;

	@Enumerated(EnumType.STRING) 
	@Column(name = "to_status", nullable = false) 
	private IssueHistoryStatus toStatus;

	@Enumerated(EnumType.STRING) 
	@Column(name = "actor_type", nullable = false) 
	private HistoryActorType actorType; 
	
	@Column(name = "actor_id") 
	private Long actorId;

	@Column(length = 200) 
	private String reason; 
	
	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false) 
	private LocalDateTime createdAt;
	
	@Builder
	private CouponIssueHistory(CouponIssue couponIssue, long couponId, long userId,
			IssueHistoryStatus fromStatus, IssueHistoryStatus toStatus,
			HistoryActorType actorType, Long actorId, String reason) {
		this.couponIssue = couponIssue;
		this.couponId = couponId;
		this.userId = userId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.actorType = actorType;
		this.actorId = actorId;
		this.reason = reason;
	}
}
