package com.mycom.petcoupon.coupon.dto.res;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.Builder;

// 실패 큐(DLQ) 목록 페이지네이션(#174) — CouponPageResponse(관리자 쿠폰 목록, #106)와
// 완전히 같은 모양이다. Page<T>를 API 레이어에 직접 노출하지 않는 이 프로젝트 컨벤션을
// 그대로 따른다.
@Builder
public record CouponIssueDlqPageResponse(
		List<CouponIssueDlqResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean first,
		boolean last
) {
	public static CouponIssueDlqPageResponse from(Page<CouponIssueDlqResponse> dlqPage) {
		return CouponIssueDlqPageResponse.builder()
				.content(List.copyOf(dlqPage.getContent()))
				.page(dlqPage.getNumber())
				.size(dlqPage.getSize())
				.totalElements(dlqPage.getTotalElements())
				.totalPages(dlqPage.getTotalPages())
				.first(dlqPage.isFirst())
				.last(dlqPage.isLast())
				.build();
	}
}
