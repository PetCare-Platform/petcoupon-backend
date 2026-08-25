package com.mycom.petcoupon.coupon.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity 
@Getter 
@Table(name = "coupon_stock")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponStock {
	
	@Id 
	@Column(name = "coupon_id") 
	private Long couponId;
	
    @MapsId 
    @OneToOne(fetch = FetchType.LAZY, optional = false) 
    @JoinColumn(name = "coupon_id") 
    private Coupon coupon;
    
    @Column(name = "total_quantity", nullable = false) 
    private int totalQuantity;
    
    @Column(name = "issued_quantity", nullable = false) 
    private int issuedQuantity;
    
    @Column(name = "remaining_quantity", nullable = false) 
    private int remainingQuantity;
    
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false) 
    private LocalDateTime updatedAt;

	@Builder
	public CouponStock(Coupon coupon, int totalQuantity) {
		this.coupon = coupon;
		this.totalQuantity = totalQuantity;
		this.issuedQuantity = 0;
		this.remainingQuantity = totalQuantity;
	}

	public void updateTotalQuantity(int totalQuantity) {
		this.totalQuantity = totalQuantity;
		this.remainingQuantity = totalQuantity - this.issuedQuantity;
	}
}
