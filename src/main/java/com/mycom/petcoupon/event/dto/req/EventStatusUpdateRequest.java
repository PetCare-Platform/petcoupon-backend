package com.mycom.petcoupon.event.dto.req;

import com.mycom.petcoupon.event.entity.enums.EventStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EventStatusUpdateRequest(
		@NotNull(message = "상태값은 필수입니다.")
		EventStatus status,

		@Size(max = 200, message = "사유는 200자 이하여야 합니다.")
		String reason
) {
}
