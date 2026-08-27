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
import lombok.Builder;
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
	private NotificationStatus status = NotificationStatus.PENDING;
	 
	@Column(name = "sent_at") 
	private LocalDateTime sentAt; 
	
	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private NotificationLog(CouponIssue couponIssue, AppUser user, Channel channel,
			String recipientMasked, String content, NotificationStatus status, LocalDateTime sentAt) {
		this.couponIssue = couponIssue;
		this.user = user;
		this.channel = channel;
		this.recipientMasked = recipientMasked;
		this.content = content;
		// status 필드의 인라인 기본값(PENDING)이 빌더 생성자에서 무조건 덮어써지지 않도록 null이면 유지.
		// @Builder.Default는 @Builder가 클래스 레벨에 있어야 동작하는데, 이 엔티티는 노출할 필드를
		// 제한하려고 @Builder를 생성자에만 붙이는 패턴이라 적용할 수 없다(실측 확인 — 컴파일 에러 남).
		if (status != null) {
			this.status = status;
		}
		this.sentAt = sentAt;
	}
}
