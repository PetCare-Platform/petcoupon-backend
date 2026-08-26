package com.mycom.petcoupon.coupon.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;
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

// @DynamicUpdate: 총수량만 바꾸는 수정 API의 flush가 발급이 갱신한 issued_quantity까지
// 낡은 값으로 덮어쓰지 않도록 한다(Coupon과 같은 이유).
//
// 주의 — 이것만으로는 동시 발급에 안전하지 않다. updateTotalQuantity가 remainingQuantity를
// 낡은 issuedQuantity로 계산하므로, 그 사이 발급이 끼어들면 remaining이 1 커진 채 기록되어
// total = issued + remaining 불변식이 깨진다. 그 상황 자체를 막는 건 수정 API가 잡는
// 비관적 락(CouponStockRepository.findByIdForUpdate)이다. 락을 빼면 안 된다.
@Entity
@Getter
@DynamicUpdate
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
