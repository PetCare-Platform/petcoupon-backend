package com.mycom.petcoupon.event.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCouponResponse;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventListResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.dto.res.PublicEventDetailResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.user.entity.AppUser;

@Component
public class EventConverter {

	public Event toEntity(EventCreateRequest request, AppUser createdBy) {
		return Event.builder()
				.createdBy(createdBy)
				.name(request.name())
				.description(request.description())
				.openAt(request.openAt())
				.closeAt(request.closeAt())
				.build();
	}

	public EventCreateResponse toCreateResponse(Event event) {
		return EventCreateResponse.builder()
				.eventId(event.getEventId())
				.name(event.getName())
				.description(event.getDescription())
				.openAt(event.getOpenAt())
				.closeAt(event.getCloseAt())
				.status(event.getStatus())
				.build();
	}

	public EventDetailResponse toDetailResponse(Event event) {
		return EventDetailResponse.builder()
				.eventId(event.getEventId())
				.name(event.getName())
				.description(event.getDescription())
				.openAt(event.getOpenAt())
				.closeAt(event.getCloseAt())
				.status(event.getStatus())
				.build();
	}

	public EventListResponse toListResponse(Event event) {
		return EventListResponse.builder()
				.eventId(event.getEventId())
				.name(event.getName())
				.description(event.getDescription())
				.openAt(event.getOpenAt())
				.closeAt(event.getCloseAt())
				.status(event.getStatus())
				.build();
	}

	public EventUpdateResponse toUpdateResponse(Event event) {
		return EventUpdateResponse.builder()
				.eventId(event.getEventId())
				.name(event.getName())
				.description(event.getDescription())
				.openAt(event.getOpenAt())
				.closeAt(event.getCloseAt())
				.status(event.getStatus())
				.build();
	}

	public EventStatusResponse toStatusResponse(Long eventId, EventStatus status) {
		return EventStatusResponse.builder()
				.eventId(eventId)
				.status(status)
				.build();
	}

	public PublicEventDetailResponse toPublicEventDetailResponse(Event event, List<Coupon> coupons) {
		return PublicEventDetailResponse.builder()
				.eventId(event.getEventId())
				.name(event.getName())
				.description(event.getDescription())
				.openAt(event.getOpenAt())
				.closeAt(event.getCloseAt())
				.status(event.getStatus())
				.coupons(coupons.stream().map(this::toEventCouponResponse).toList())
				.build();
	}

	public EventCouponResponse toEventCouponResponse(Coupon coupon) {
		return EventCouponResponse.builder()
				.couponId(coupon.getCouponId())
				.name(coupon.getName())
				.discountType(coupon.getDiscountType())
				.discountValue(coupon.getDiscountValue())
				.minOrderAmount(coupon.getMinOrderAmount())
				.maxDiscountAmount(coupon.getMaxDiscountAmount())
				.issueStartAt(coupon.getIssueStartAt())
				.issueEndAt(coupon.getIssueEndAt())
				.validDays(coupon.getValidDays())
				.status(coupon.getStatus())
				.build();
	}
}
