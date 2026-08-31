package com.mycom.petcoupon.event.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

import com.mycom.petcoupon.event.entity.enums.EventStatus;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// @DynamicUpdate: 기본 동작인 전체 컬럼 UPDATE는 updateEvent가 건드리지도 않은 status까지 쓴다.
// 그래서 락 없이 읽으면 그 사이 updateStatusIfMatches(관리자 상태 변경·스케줄러)가 만든 OPEN을
// 영속성 컨텍스트의 낡은 SCHEDULED로 되돌릴 수 있다. 지금은 findByIdForUpdate의 비관적 락이
// 그 창을 막고 있지만, 락이 유일한 방어선이면 나중에 읽는 경로가 하나 늘어나는 순간 되살아난다.
// 변경된 컬럼만 UPDATE하도록 바꿔서 그 경로를 아예 없앤다(Coupon과 같은 이유·같은 처방).
@Entity
@Getter
@DynamicUpdate
@Table(name = "event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {
	
	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "event_id") 
	private Long eventId;
	
    @ManyToOne(fetch = FetchType.LAZY, optional = false) 
    @JoinColumn(name = "created_by") 
    private AppUser createdBy;
    
    @Column(nullable = false, length = 100) 
    private String name;
    
    @Column(length = 500) 
    private String description;
    
    @Column(name = "open_at", nullable = false) 
    private LocalDateTime openAt;
    
    @Column(name = "close_at", nullable = false) 
    private LocalDateTime closeAt;
    
    @Enumerated(EnumType.STRING) 
    @Column(nullable = false, length = 20) 
    private EventStatus status = EventStatus.SCHEDULED;

	@Builder
	public Event(
			AppUser createdBy,
			String name,
			String description,
			LocalDateTime openAt,
			LocalDateTime closeAt
	) {
		this.createdBy = createdBy;
		this.name = name;
		this.description = description;
		this.openAt = openAt;
		this.closeAt = closeAt;
		this.status = EventStatus.SCHEDULED;
	}

	public void updateName(String name) {
		this.name = name;
	}

	public void updateDescription(String description) {
		this.description = description;
	}

	public void updatePeriod(LocalDateTime openAt, LocalDateTime closeAt) {
		this.openAt = openAt;
		this.closeAt = closeAt;
	}

	public void updateStatus(EventStatus status) {
		this.status = status;
	}
}
