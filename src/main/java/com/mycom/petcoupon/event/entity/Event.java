package com.mycom.petcoupon.event.entity;

import java.time.LocalDateTime;

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

@Entity 
@Getter 
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
    public Event(AppUser createdBy, String name, String description, LocalDateTime openAt, LocalDateTime closeAt) {
        this.createdBy=createdBy; 
        this.name=name; 
        this.description=description; 
        this.openAt=openAt; 
        this.closeAt=closeAt;
    }
}
