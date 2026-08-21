package com.mycom.petcoupon.event.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Builder;

@Builder
public record EventNameUpdateRequest(
		@NotBlank(message = "이벤트 이름은 필수입니다.")
		@Size(max = 100, message = "이벤트 이름은 100자 이하여야 합니다.")
		String name
) {
}
