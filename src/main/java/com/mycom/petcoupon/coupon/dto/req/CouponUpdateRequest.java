package com.mycom.petcoupon.coupon.dto.req;

import java.time.LocalDateTime;

import com.mycom.petcoupon.coupon.entity.enums.DiscountType;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record CouponUpdateRequest(
		@Size(max = 100, message = "쿠폰 이름은 100자 이하여야 합니다.")
		String name,

		DiscountType discountType,

		@Positive(message = "할인 값은 0보다 커야 합니다.")
		Integer discountValue,

		@PositiveOrZero(message = "최소 주문 금액은 0 이상이어야 합니다.")
		Integer minOrderAmount,

		@Positive(message = "최대 할인 금액은 0보다 커야 합니다.")
		Integer maxDiscountAmount,

		LocalDateTime issueStartAt,

		LocalDateTime issueEndAt,

		@Positive(message = "쿠폰 유효 일수는 0보다 커야 합니다.")
		Integer validDays,

		@Positive(message = "쿠폰 총수량은 0보다 커야 합니다.")
		Integer totalQuantity
) {
	public boolean isEmpty() {
		return name == null
				&& discountType == null
				&& discountValue == null
				&& minOrderAmount == null
				&& maxDiscountAmount == null
				&& issueStartAt == null
				&& issueEndAt == null
				&& validDays == null
				&& totalQuantity == null;
	}
}
