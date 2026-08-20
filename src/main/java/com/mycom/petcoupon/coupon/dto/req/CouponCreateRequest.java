package com.mycom.petcoupon.coupon.dto.req;

import java.time.LocalDateTime;

import com.mycom.petcoupon.coupon.entity.enums.DiscountType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record CouponCreateRequest(
		@NotBlank(message = "쿠폰 이름은 필수입니다.")
		@Size(max = 100, message = "쿠폰 이름은 100자 이하여야 합니다.")
		String name,

		@NotNull(message = "할인 유형은 필수입니다.")
		DiscountType discountType,

		@NotNull(message = "할인 값은 필수입니다.")
		@Positive(message = "할인 값은 0보다 커야 합니다.")
		Integer discountValue,

		@NotNull(message = "최소 주문 금액은 필수입니다.")
		@PositiveOrZero(message = "최소 주문 금액은 0 이상이어야 합니다.")
		Integer minOrderAmount,

		@Positive(message = "최대 할인 금액은 0보다 커야 합니다.")
		Integer maxDiscountAmount,

		@NotNull(message = "쿠폰 발급 시작 시각은 필수입니다.")
		LocalDateTime issueStartAt,

		@NotNull(message = "쿠폰 발급 종료 시각은 필수입니다.")
		LocalDateTime issueEndAt,

		@NotNull(message = "쿠폰 유효 일수는 필수입니다.")
		@Positive(message = "쿠폰 유효 일수는 0보다 커야 합니다.")
		Integer validDays,

		@NotNull(message = "쿠폰 총수량은 필수입니다.")
		@Positive(message = "쿠폰 총수량은 0보다 커야 합니다.")
		Integer totalQuantity
) {
}
