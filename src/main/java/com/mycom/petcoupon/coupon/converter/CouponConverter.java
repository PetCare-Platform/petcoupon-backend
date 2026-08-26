package com.mycom.petcoupon.coupon.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
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
}
