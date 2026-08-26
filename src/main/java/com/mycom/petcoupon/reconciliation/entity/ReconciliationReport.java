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

	// 발급 건(coupon_issue row) 단위로 문제가 있는 건수만 센다. STOCK_MISMATCH/SEQUENCE_GAP처럼
	// 특정 발급 건이 아니라 쿠폰 전체를 가리키는 문제는 couponIssueId가 없어서 여기 안 잡힌다.
	// 그래서 errorCount==0 이어도 result가 MISMATCHED일 수 있다 — "문제가 있는지"는 반드시
	// result로 판단하고, errorCount는 "발급 건 몇 개가 걸렸는지"를 보는 보조 지표로만 쓴다.
	@Column(name = "error_count", nullable = false)
	private long errorCount;

	// null은 "미검증"(Redis 키가 아예 없는 등), 0은 "검증했고 실제로 0건"을 뜻하므로 구분해야 함
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

	// Batch Job에서는 이 row를 먼저 INSERT해둬야 청크 Step들이 report_id를 참조할 수 있는데,
	// errorCount/successCount/result는 모든 Step이 끝나야 정해진다. 그래서 최초 INSERT 때는
	// 임시값을 넣어두고, 마지막 Step(FinalizeReportTasklet)에서 이 메서드로 확정값을 채운다.
	public void finalizeCounts(long successCount, long errorCount, ReconciliationResult result, LocalDateTime finishedAt) {
		this.successCount = successCount;
		this.errorCount = errorCount;
		this.result = result;
		this.finishedAt = finishedAt;
	}

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
