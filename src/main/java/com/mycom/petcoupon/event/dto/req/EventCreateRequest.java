package com.mycom.petcoupon.event.dto.req;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EventCreateRequest(
		@NotBlank(message = "이벤트 이름은 필수입니다.")
		@Size(max = 100, message = "이벤트 이름은 100자 이하여야 합니다.")
		String name,

		@Size(max = 500, message = "이벤트 설명은 500자 이하여야 합니다.")
		String description,

		@NotNull(message = "이벤트 오픈 시각은 필수입니다.")
		LocalDateTime openAt,

		@NotNull(message = "이벤트 종료 시각은 필수입니다.")
		LocalDateTime closeAt
) {
}
