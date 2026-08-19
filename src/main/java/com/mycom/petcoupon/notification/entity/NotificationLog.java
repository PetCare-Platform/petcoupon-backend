package com.mycom.petcoupon.notification.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.notification.entity.enums.Channel;
import com.mycom.petcoupon.notification.entity.enums.NotificationStatus;
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
	name = "notification_log",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_noti_issue_channel",
			columnNames = {"coupon_issue_id", "channel"}
		)
	}
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class NotificationLog {

	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "notification_id") 
	private Long notificationId; 
	
	@ManyToOne(fetch = FetchType.LAZY) 
	@JoinColumn(name = "coupon_issue_id") 
	private CouponIssue couponIssue;
	 
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id") 
	private AppUser user; 
	
	@Enumerated(EnumType.STRING) 
	@Column(nullable = false, length = 20) 
	private Channel channel;
	 
	@Column(name = "recipient_masked", nullable = false, length = 100) 
	private String recipientMasked; 
	
	@Column(length = 500) 
	private String content; 
	
	@Enumerated(EnumType.STRING) 
	@Column(nullable = false, length = 20) 
	private NotificationStatus status=NotificationStatus.PENDING;
	 
	@Column(name = "sent_at") 
	private LocalDateTime sentAt; 
	
	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false) 
	private LocalDateTime createdAt;
}
