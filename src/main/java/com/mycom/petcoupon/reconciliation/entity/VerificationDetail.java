package com.mycom.petcoupon.reconciliation.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;

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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity 
@Getter 
@Table(name = "verification_detail")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class VerificationDetail {

	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "detail_id") 
	private Long detailId; 
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false) 
	@JoinColumn(name = "report_id") 
	private ReconciliationReport report;
	 
	@Enumerated(EnumType.STRING) 
	@Column(name = "error_type", nullable = false, length = 30) 
	private VerificationErrorType errorType; 
	
	@Column(name = "coupon_issue_id") 
	private Long couponIssueId; 
	
	@Column(name = "user_id") 
	private Long userId;
	 
	@Column(name = "expected_value", length = 100) 
	private String expectedValue; 
	
	@Column(name = "actual_value", length = 100) 
	private String actualValue; 
	
	@Column(length = 500) 
	private String message; 
	
	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public void assignReport(ReconciliationReport report) {
	    this.report = report;
	    report.getVerificationDetails().add(this);
	}
	
	@Builder
	private VerificationDetail(
			ReconciliationReport report, VerificationErrorType errorType,
			Long couponIssueId, Long userId, String expectedValue, String actualValue, String message
	) {
		this.report = report;
		this.errorType = errorType;
		this.couponIssueId = couponIssueId;
		this.userId = userId;
		this.expectedValue = expectedValue;
		this.actualValue = actualValue;
		this.message = message;
	}
}
