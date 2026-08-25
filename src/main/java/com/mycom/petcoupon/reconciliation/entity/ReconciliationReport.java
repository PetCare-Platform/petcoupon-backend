package com.mycom.petcoupon.reconciliation.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.reconciliation.entity.enums.ReconciliationResult;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity 
@Getter
@Table(
	name = "reconciliation_report",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_report_snapshot",
			columnNames = {"coupon_id", "as_of_at"}
	    )
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class ReconciliationReport {
	
	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "report_id") 
	private Long reportId; 
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false) 
	@JoinColumn(name = "coupon_id") 
	private Coupon coupon;
	
	@Column(name = "as_of_at", nullable = false) 
	private LocalDateTime asOfAt; 
	
	@Column(name = "started_at", nullable = false) 
	private LocalDateTime startedAt; 
	
	@Column(name = "finished_at") 
	private LocalDateTime finishedAt;
	 
	@Column(name = "total_count", nullable = false) 
	private long totalCount; 
	
	@Column(name = "success_count", nullable = false) 
	private long successCount; 
	
	@Column(name = "error_count", nullable = false) 
	private long errorCount;
	 
	// 이번 범위(#57/#58 파이프라인 대기)에서 아직 계산할 데이터가 없어 null 허용 —
	// null은 "미검증", 0은 "검증했고 실제로 0건"을 뜻하므로 구분해야 함
	@Column(name = "stock_total")
	private Integer stockTotal;

	@Column(name = "stock_issued")
	private Integer stockIssued;

	@Column(name = "stock_remaining")
	private Integer stockRemaining;

	@Column(name = "db_active_count", nullable = false)
	private long dbActiveCount;

	@Column(name = "db_expired_count", nullable = false)
	private long dbExpiredCount;

	@Column(name = "db_dlq_count")
	private Long dbDlqCount;
	 
	@Column(name = "max_sequence_no") 
	private Long maxSequenceNo; 
	
	@Column(name = "redis_remaining") 
	private Integer redisRemaining; 
	
	@OneToMany(
		mappedBy = "report",
		cascade = CascadeType.ALL,
		orphanRemoval = true
	)
	private List<VerificationDetail> verificationDetails = new ArrayList<>();
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReconciliationResult result;

	@Builder
	private ReconciliationReport(
			Coupon coupon, LocalDateTime asOfAt, LocalDateTime startedAt, LocalDateTime finishedAt,
			long totalCount, long successCount, long errorCount,
			Integer stockTotal, Integer stockIssued, Integer stockRemaining,
			long dbActiveCount, long dbExpiredCount, Long dbDlqCount,
			Long maxSequenceNo, Integer redisRemaining, ReconciliationResult result
	) {
		this.coupon = coupon;
		this.asOfAt = asOfAt;
		this.startedAt = startedAt;
		this.finishedAt = finishedAt;
		this.totalCount = totalCount;
		this.successCount = successCount;
		this.errorCount = errorCount;
		this.stockTotal = stockTotal;
		this.stockIssued = stockIssued;
		this.stockRemaining = stockRemaining;
		this.dbActiveCount = dbActiveCount;
		this.dbExpiredCount = dbExpiredCount;
		this.dbDlqCount = dbDlqCount;
		this.maxSequenceNo = maxSequenceNo;
		this.redisRemaining = redisRemaining;
		this.result = result;
	}
}
