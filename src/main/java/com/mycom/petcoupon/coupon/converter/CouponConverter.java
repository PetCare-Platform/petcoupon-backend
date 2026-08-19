package com.mycom.petcoupon.coupon.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
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

	public CouponCreateResponse toResponse(Coupon coupon, CouponStock couponStock) {
		return new CouponCreateResponse(
				coupon.getCouponId(),
				coupon.getEvent().getEventId(),
				coupon.getName(),
				coupon.getDiscountType(),
				coupon.getDiscountValue(),
				coupon.getMinOrderAmount(),
				coupon.getMaxDiscountAmount(),
				coupon.getIssueStartAt(),
				coupon.getIssueEndAt(),
				coupon.getValidDays(),
				couponStock.getTotalQuantity(),
				coupon.getStatus()
		);
	}
}
