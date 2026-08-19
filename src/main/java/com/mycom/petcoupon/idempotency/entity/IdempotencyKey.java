package com.mycom.petcoupon.idempotency.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.user.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity 
@Getter 
@Table(
	name = "idempotency_key",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_idem_user_key", 
			columnNames = {"user_id","idempotency_key"}
		)
	}
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class IdempotencyKey {
	
	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "idempotency_id") 
	private Long idempotencyId; 
	
	@Column(name = "idempotency_key", nullable = false, length = 64) 
	private String idempotencyKey;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false) 
	@JoinColumn(name = "user_id") 
	private AppUser user; 
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false) 
	@JoinColumn(name = "coupon_id") 
	private Coupon coupon;
	
	@ManyToOne(fetch = FetchType.LAZY) 
	@JoinColumn(name = "coupon_issue_id") 
	private CouponIssue couponIssue; 
	
	@Column(name = "request_hash", nullable = false, length = 64) 
	private String requestHash;
	
	@Column(name = "response_body", columnDefinition = "json") 
	private String responseBody; 
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false) 
	private IdempotencyStatus status = IdempotencyStatus.IN_PROGRESS;
	
	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false) 
	private LocalDateTime createdAt; 
	
	@Column(name = "expires_at", nullable = false) 
	private LocalDateTime expiresAt;
}	
