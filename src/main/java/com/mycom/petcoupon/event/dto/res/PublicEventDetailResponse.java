package com.mycom.petcoupon.event.dto.res;

import java.time.LocalDateTime;
import java.util.List;

import com.mycom.petcoupon.event.entity.enums.EventStatus;

import lombok.Builder;

/**
 * 공개 이벤트 상세(GET /events/{eventId}) 응답.
 *
 * 관리자용 {@link EventDetailResponse}와 계약을 분리한다. 관리자는 모든 상태의 이벤트를
 * 조회하지만 이 응답은 OPEN 이벤트만 도달하고, 해당 이벤트에 연결된 쿠폰 기본정보 목록을
 * 함께 싣는다. 연결된 쿠폰이 없으면 {@code coupons}는 빈 목록이다.
 */
@Builder
public record PublicEventDetailResponse(
		Long eventId,
		String name,
		String description,
		LocalDateTime openAt,
		LocalDateTime closeAt,
		EventStatus status,
		List<EventCouponResponse> coupons
) {
}
