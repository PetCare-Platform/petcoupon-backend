package com.mycom.petcoupon.event.dto.req;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

import lombok.Builder;

@Builder
public record EventPeriodUpdateRequest(
		@NotNull(message = "시작 일시는 필수입니다.")
		LocalDateTime openAt,

		@NotNull(message = "종료 일시는 필수입니다.")
		LocalDateTime closeAt
) {
}
