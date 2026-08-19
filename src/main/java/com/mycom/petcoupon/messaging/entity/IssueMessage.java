package com.mycom.petcoupon.messaging.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.coupon.entity.Coupon;
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
}
