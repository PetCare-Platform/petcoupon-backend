package com.mycom.petcoupon.coupon.converter;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponListResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponPipelineDrainStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponUpdateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.service.PipelineDrainStatus;
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

	public CouponListResponse toListResponse(Coupon coupon, CouponStock couponStock) {
		Event event = coupon.getEvent();

		return CouponListResponse.builder()
				.couponId(coupon.getCouponId())
				.eventId(event.getEventId())
				.eventName(event.getName())
				.name(coupon.getName())
				.discountType(coupon.getDiscountType())
				.discountValue(coupon.getDiscountValue())
				.minOrderAmount(coupon.getMinOrderAmount())
				.maxDiscountAmount(coupon.getMaxDiscountAmount())
				.issueStartAt(coupon.getIssueStartAt())
				.issueEndAt(coupon.getIssueEndAt())
				.validDays(coupon.getValidDays())
				.status(coupon.getStatus())
				.totalQuantity(couponStock.getTotalQuantity())
				.issuedQuantity(couponStock.getIssuedQuantity())
				.remainingQuantity(couponStock.getRemainingQuantity())
				.stockUpdatedAt(couponStock.getUpdatedAt())
				.build();
	}

	public CouponRealtimeStatusResponse toRealtimeStatusResponse(
			Coupon coupon, CouponStock couponStock, CouponIssueRealtimeStock realtimeStock) {
		int totalQuantity = couponStock.getTotalQuantity();

		// Redis가 아직 초기화 안 된 상태(발급 시작 전)면 Lua가 발급 자체를 막아주므로
		// 실제로는 아무도 발급받지 못한 게 맞다 — 잔여를 총수량 그대로 노출한다.
		int remainingQuantity = realtimeStock.initialized() ? realtimeStock.remainingStock() : totalQuantity;
		int issuedQuantity = totalQuantity - remainingQuantity;

		return CouponRealtimeStatusResponse.builder()
				.couponId(coupon.getCouponId())
				.totalQuantity(totalQuantity)
				.remainingQuantity(remainingQuantity)
				.issuedQuantity(issuedQuantity)
				.initialized(realtimeStock.initialized())
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

	public CouponPipelineDrainStatusResponse toPipelineDrainStatusResponse(
			Coupon coupon, PipelineDrainStatus drainStatus) {
		return CouponPipelineDrainStatusResponse.builder()
				.couponStatus(coupon.getStatus())
				.outboxUnconsumed(drainStatus.outboxUnconsumed())
				.streamUndelivered(drainStatus.streamUndelivered())
				.streamActivePending(drainStatus.streamActivePending())
				.checkFailed(drainStatus.checkFailed())
				.build();
	}
}
