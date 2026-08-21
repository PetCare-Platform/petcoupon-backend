package com.mycom.petcoupon.coupon.entity;

import java.time.LocalDateTime;

import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.global.entity.BaseEntity;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity 
@Getter 
@Table(
		name = "coupon_issue",
		indexes = {
			@Index(name = "idx_issue_expire", columnList = "status, expires_at")
		},
		uniqueConstraints = {
			@UniqueConstraint(
					name = "uk_issue_coupon_user",
		            columnNames = {"coupon_id", "user_id"}
		    ),
			@UniqueConstraint(
		            name = "uk_issue_sequence",
		            columnNames = {"coupon_id", "sequence_no"}
		    )
		}
	)@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class CouponIssue extends BaseEntity {
	
	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "coupon_issue_id") 
	private Long couponIssueId;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "coupon_id") 
	private Coupon coupon;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false) 
	@JoinColumn(name = "user_id") 
	private AppUser user;

	@Column(name = "sequence_no", nullable = false) 
	private long sequenceNo; 
	
	@Column(name = "coupon_code", nullable = false, length = 32, unique = true) 
	private String couponCode;
	
	@Column(name = "request_id", nullable = false, length = 64, unique = true) 
	private String requestId;
	
	@Enumerated(EnumType.STRING) 
	@Column(nullable = false, length = 20) 
	private IssueStatus status = IssueStatus.ISSUED;
	
	@Column(name = "used_at") 
	private LocalDateTime usedAt; 
	
	@Column(name = "canceled_at") 
	private LocalDateTime canceledAt;
	
	@Column(name = "expires_at", nullable = false) 
	private LocalDateTime expiresAt; 
	
	@Builder
	private CouponIssue(Coupon coupon, AppUser user, long sequenceNo, String couponCode,
			String requestId, IssueStatus status, LocalDateTime usedAt, LocalDateTime canceledAt,
			LocalDateTime expiresAt) {
		this.coupon = coupon;
		this.user = user;
		this.sequenceNo = sequenceNo;
		this.couponCode = couponCode;
		this.requestId = requestId;
		this.status = status == null ? IssueStatus.ISSUED : status;
		this.usedAt = usedAt;
		this.canceledAt = canceledAt;
		this.expiresAt = expiresAt;
	}
}
