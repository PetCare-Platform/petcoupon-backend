package com.mycom.petcoupon.coupon.converter;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponUpdateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.event.entity.Event;

@Component
public class CouponConverter {

	public Coupon toCoupon(Event event, CouponCreateRequest request) {
		return Coupon.builder()
				.event(event)
				.name(request.name())
				.discountType(request.discountType())
				.discountValue(request.discountValue())
				.minOrderAmount(request.minOrderAmount())
				.maxDiscountAmount(request.maxDiscountAmount())
				.issueStartAt(request.issueStartAt())
				.issueEndAt(request.issueEndAt())
				.validDays(request.validDays())
				.build();
	}

	public CouponStock toCouponStock(Coupon coupon, CouponCreateRequest request) {
		return CouponStock.builder()
				.coupon(coupon)
				.totalQuantity(request.totalQuantity())
				.build();
	}

	public CouponCreateResponse toCreateResponse(Coupon coupon, CouponStock couponStock) {
		return CouponCreateResponse.builder()
				.couponId(coupon.getCouponId())
				.eventId(coupon.getEvent().getEventId())
				.name(coupon.getName())
				.discountType(coupon.getDiscountType())
				.discountValue(coupon.getDiscountValue())
				.minOrderAmount(coupon.getMinOrderAmount())
				.maxDiscountAmount(coupon.getMaxDiscountAmount())
				.issueStartAt(coupon.getIssueStartAt())
				.issueEndAt(coupon.getIssueEndAt())
				.validDays(coupon.getValidDays())
				.totalQuantity(couponStock.getTotalQuantity())
				.status(coupon.getStatus())
				.build();
	}

	public CouponRealtimeStatusResponse toRealtimeStatusResponse(
			Coupon coupon, CouponStock couponStock, CouponIssueRealtimeStock realtimeStock) {
		return CouponRealtimeStatusResponse.builder()
				.couponId(coupon.getCouponId())
				.totalQuantity(couponStock.getTotalQuantity())
				.remainingQuantity(realtimeStock.remainingStock())
				.issuedQuantity(realtimeStock.issuedCount())
				.build();
	}

	public CouponUpdateResponse toUpdateResponse(Coupon coupon, CouponStock couponStock) {
		return CouponUpdateResponse.builder()
				.couponId(coupon.getCouponId())
				.eventId(coupon.getEvent().getEventId())
				.name(coupon.getName())
				.discountType(coupon.getDiscountType())
				.discountValue(coupon.getDiscountValue())
				.minOrderAmount(coupon.getMinOrderAmount())
				.maxDiscountAmount(coupon.getMaxDiscountAmount())
				.issueStartAt(coupon.getIssueStartAt())
				.issueEndAt(coupon.getIssueEndAt())
				.validDays(coupon.getValidDays())
				.totalQuantity(couponStock.getTotalQuantity())
				.status(coupon.getStatus())
				.updatedAt(latestUpdatedAt(coupon, couponStock))
				.build();
	}

	// 응답은 쿠폰 정책과 총수량을 함께 담으므로, 수정 시각도 둘 중 나중 것을 쓴다.
	// 총수량만 바꾼 요청은 Coupon에 실제 변경이 없어 coupon.updatedAt이 갱신되지 않는다.
	private LocalDateTime latestUpdatedAt(Coupon coupon, CouponStock couponStock) {
		LocalDateTime couponUpdatedAt = coupon.getUpdatedAt();
		LocalDateTime stockUpdatedAt = couponStock.getUpdatedAt();

		if (couponUpdatedAt == null) {
			return stockUpdatedAt;
		}

		if (stockUpdatedAt == null) {
			return couponUpdatedAt;
		}

		return couponUpdatedAt.isAfter(stockUpdatedAt) ? couponUpdatedAt : stockUpdatedAt;
	}
}
