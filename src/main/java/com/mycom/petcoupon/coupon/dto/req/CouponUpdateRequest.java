package com.mycom.petcoupon.coupon.dto.req;

import java.time.LocalDateTime;

import com.mycom.petcoupon.coupon.entity.enums.DiscountType;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record CouponUpdateRequest(
		// @NotBlank는 null까지 거부해서 "생략 가능" 의미가 깨진다. @Pattern은 null을 통과시키므로
		// 필드를 안 보내는 건 허용하고, 보냈는데 전부 공백인 경우만 막는다(생성 API의 @NotBlank와 대응).
		// (?s)가 없으면 '.'이 줄바꿈에 매칭되지 않아 "가을\n쿠폰" 같은 멀쩡한 이름까지 거부된다.
		@Pattern(regexp = "(?s).*\\S.*", message = "쿠폰 이름은 공백일 수 없습니다.")
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
