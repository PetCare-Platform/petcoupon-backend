package com.mycom.petcoupon.event.dto.res;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.Builder;

@Builder
public record EventPageResponse(
		List<EventListResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean first,
		boolean last
) {
	public static EventPageResponse from(Page<EventListResponse> eventPage) {
		return EventPageResponse.builder()
				.content(List.copyOf(eventPage.getContent()))
				.page(eventPage.getNumber())
				.size(eventPage.getSize())
				.totalElements(eventPage.getTotalElements())
				.totalPages(eventPage.getTotalPages())
				.first(eventPage.isFirst())
				.last(eventPage.isLast())
				.build();
	}
}
