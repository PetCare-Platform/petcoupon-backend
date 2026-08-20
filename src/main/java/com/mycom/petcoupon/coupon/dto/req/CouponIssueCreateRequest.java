package com.mycom.petcoupon.coupon.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

/**
 * 선착순 쿠폰 신청 요청 DTO.
 * 로그인 기능이 없는 프로젝트라 신청자 userId를 클라이언트가 직접 실어 보낸다.
 */
@Builder
public record CouponIssueCreateRequest(
    @NotNull @Positive Long userId
) {
}
