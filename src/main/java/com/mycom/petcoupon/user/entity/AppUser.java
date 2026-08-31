package com.mycom.petcoupon.user.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.entity.enums.UserStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity 
@Getter
@Table(name = "app_user")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {
	
	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id") 
	private Long userId;
	
    @Column(nullable = false, unique = true, length = 36) 
    private String uuid;
    
    @Column(nullable = false, length = 50) 
    private String name;
    
    @Column(length = 255) 
    private String email;
    
    @Column(length = 20) 
    private String phone;
    
    @Enumerated(EnumType.STRING) 
    @Column(nullable = false, length = 20) 
    private UserRole role = UserRole.ROLE_MEMBER;
    
    @Enumerated(EnumType.STRING) 
    @Column(nullable = false, length = 20) 
    private UserStatus status = UserStatus.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Builder
    public AppUser(String name, String email, String phone, UserRole role) {
        this.uuid = UUID.randomUUID().toString(); 
        this.name = name; 
        this.email = email; 
        this.phone = phone;
        this.role = role == null ? UserRole.ROLE_MEMBER : role;
    }
}
