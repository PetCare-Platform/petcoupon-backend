package com.mycom.petcoupon.coupon.entity;

import java.time.LocalDateTime;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.global.entity.BaseEntity;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity 
@Getter 
@Table(name = "coupon")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseEntity {
	
	@Id 
	@GeneratedValue(strategy = GenerationType.IDENTITY) 
	@Column(name = "coupon_id") 
	private Long couponId;
	
    @ManyToOne(fetch = FetchType.LAZY, optional = false) 
    @JoinColumn(name = "event_id") 
    private Event event;
    
    @Column(nullable = false, length = 100) 
    private String name;
    
    @Enumerated(EnumType.STRING) 
    @Column(name = "discount_type", nullable = false, length = 20) 
    private DiscountType discountType;
    
    @Column(name = "discount_value", nullable = false) 
    private int discountValue;
    
    @Column(name = "min_order_amount", nullable = false) 
    private int minOrderAmount;
    
    @Column(name = "max_discount_amount") 
    private Integer maxDiscountAmount;
    
    @Column(name = "issue_start_at", nullable = false) 
    private LocalDateTime issueStartAt;
    
    @Column(name = "issue_end_at", nullable = false) 
    private LocalDateTime issueEndAt;
    
    @Column(name = "valid_days", nullable = false) 
    private int validDays = 7;
    
    // 현재 정책: 사용자 1명당 쿠폰 1장만 발급 가능
    @Column(name = "limit_per_member", nullable = false) 
    private int limitPerMember = 1;
    
    @Enumerated(EnumType.STRING) 
    @Column(nullable = false, length = 20) 
    private CouponStatus status = CouponStatus.READY;
}
