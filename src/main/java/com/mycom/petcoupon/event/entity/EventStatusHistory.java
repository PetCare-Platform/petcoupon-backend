package com.mycom.petcoupon.event.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.event.entity.enums.ActorType;
import com.mycom.petcoupon.event.entity.enums.EventHistoryStatus;

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
	name = "event_status_history",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_event_history_transition",
			columnNames = {"event_id", "from_status", "to_status"}
	    )
	}
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventStatusHistory {

	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "event_history_id") 
	private Long eventHistoryId;
	
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id") 
    private Event event;
    
    @Enumerated(EnumType.STRING) 
    @Column(name = "from_status", nullable = false, length = 20) 
    private EventHistoryStatus fromStatus;
    
    @Enumerated(EnumType.STRING) 
    @Column(name = "to_status", nullable = false, length = 20) 
    private EventHistoryStatus toStatus;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20) 
    private ActorType actorType;
    
    @Column(name = "actor_id") 
    private Long actorId;
    
    @Column(length = 200) 
    private String reason;
    
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false) 
    private LocalDateTime createdAt;

	@Builder
	private EventStatusHistory(
			Event event,
			EventHistoryStatus fromStatus,
			EventHistoryStatus toStatus,
			ActorType actorType,
			Long actorId,
			String reason
	) {
		this.event = event;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.actorType = actorType;
		this.actorId = actorId;
		this.reason = reason;
	}
}
