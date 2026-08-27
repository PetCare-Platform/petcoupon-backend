package com.mycom.petcoupon.messaging.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter 
@Table(
	name = "issue_message",
	indexes = {
		@Index(
			name = "idx_issue_message_publish",
			columnList = "status, retry_count, message_id"
		),
		// 정합성 검증 배치(stockNotRestoredStep, ReconciliationJobConfig 참고)가
		// coupon_id+status='DLQ'로 좁힌 뒤 message_id 순으로 keyset 페이징한다. 이 인덱스가
		// 없으면 coupon_id만 태우는 uk_message_sequence(coupon_id, sequence_no)로 좁힌 뒤
		// 매 페이지 남은 후보 전체를 스캔+정렬해야 해서(Using filesort) 페이지 수가 늘수록
		// 비용이 O(N²/chunkSize)로 커진다 — coupon_id, status, message_id 순으로 만들면
		// 이 필터+정렬을 인덱스 하나로 커버해 페이지당 chunkSize만큼만 훑는다.
		@Index(
			name = "idx_issue_message_coupon_dlq",
			columnList = "coupon_id, status, message_id"
		),
		// 발급 처리량 조회(#156, IssueMessageRepository.findThroughputByHour)가
		// created_at >= :from AND created_at < :to로 최근 N시간만 좁혀서 대시보드 폴링용으로
		// 자주 호출되는데, 이 컬럼을 커버하는 인덱스가 없으면 "최근 것만" 보는 쿼리인데도
		// 매번 테이블 전체를 스캔한다(EXPLAIN으로 실측 확인됨). created_at 단일 인덱스로
		// 그 범위만 인덱스 레인지 스캔하도록 만든다.
		@Index(
			name = "idx_issue_message_created_at",
			columnList = "created_at"
		)
	},
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_message_key_topic",
			columnNames = {"topic", "message_key"}
	    ),
		@UniqueConstraint(
			name = "uk_message_sequence",
			columnNames = {"coupon_id", "sequence_no"}
	    )
	}
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)  
public class IssueMessage {
	
	@Id 
	@GeneratedValue(strategy=GenerationType.IDENTITY) 
	@Column(name = "message_id") 
	private Long messageId; 
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false) 
	@JoinColumn(name = "coupon_id") 
	private Coupon coupon;
	
	@Column(name = "user_id", nullable = false) 
	private long userId; 
	
	@Column(name = "sequence_no", nullable = false) 
	private long sequenceNo; 
	
	@Column(name = "message_key", nullable = false, length = 100) 
	private String messageKey; 
	
	@Column(nullable = false, length = 100) 
	private String topic;
	
	@Column(nullable = false, columnDefinition = "json") 
	private String payload; 
	
	@Enumerated(EnumType.STRING) 
	@Column(nullable = false, length = 20) 
	private IssueMessageStatus status = IssueMessageStatus.PENDING; 
	
	@Column(name = "retry_count", nullable = false) 
	private int retryCount;
	
	@Column(name = "last_error", length = 500) 
	private String lastError; 
	
	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false) 
	private LocalDateTime createdAt; 
	
	@Column(name = "processed_at")
	private LocalDateTime processedAt;

	// abandon() 처리에서 restoreStock()이 RESTORED/ALREADY_RESTORED로 확인된 뒤에만 채운다(#149).
	// status(ABANDONED)만으로는 재고 복구 성공 여부를 알 수 없다 — claimForAbandon()이 먼저
	// status를 ABANDONED로 커밋하고 그 다음에 restoreStock()을 호출하는 구조라, restoreStock()이
	// 실패해도(Redis 장애 등) status는 이미 ABANDONED로 남는다. 그래서 "복구 안 됨"은 status가
	// 아니라 이 컬럼이 null인지로 판단해야 한다 — 정합성 검증 배치(stockNotRestoredReader)가
	// 이 컬럼을 본다.
	@Column(name = "stock_restored_at")
	private LocalDateTime stockRestoredAt;

	public static IssueMessage pending(
	        Coupon coupon,
	        long userId,
	        long sequenceNo,
	        String requestId,
	        String payload
	) {
	    IssueMessage issueMessage = new IssueMessage();

	    issueMessage.coupon = coupon;
	    issueMessage.userId = userId;
	    issueMessage.sequenceNo = sequenceNo;
	    issueMessage.messageKey = requestId;
	    issueMessage.topic = KafkaTopics.COUPON_ISSUE_EVENT;
	    issueMessage.payload = payload;
	    issueMessage.status = IssueMessageStatus.PENDING;
	    issueMessage.retryCount = 0;

	    return issueMessage;
	}
}
