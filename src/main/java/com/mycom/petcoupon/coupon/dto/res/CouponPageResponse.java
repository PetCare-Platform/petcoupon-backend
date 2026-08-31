package com.mycom.petcoupon.coupon.dto.res;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.Builder;

@Builder
public record CouponPageResponse(
        List<CouponListResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static CouponPageResponse from(Page<CouponListResponse> couponPage) {
        return CouponPageResponse.builder()
                .content(List.copyOf(couponPage.getContent()))
                .page(couponPage.getNumber())
                .size(couponPage.getSize())
                .totalElements(couponPage.getTotalElements())
                .totalPages(couponPage.getTotalPages())
                .first(couponPage.isFirst())
                .last(couponPage.isLast())
                .build();
    }
}
