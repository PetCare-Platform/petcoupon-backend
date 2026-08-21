package com.mycom.petcoupon.event.dto.req;

import jakarta.validation.constraints.Size;

public record EventDescriptionUpdateRequest(
		// null이면 설명을 비운다
		@Size(max = 500, message = "이벤트 설명은 500자 이하여야 합니다.")
		String description
) {
}
