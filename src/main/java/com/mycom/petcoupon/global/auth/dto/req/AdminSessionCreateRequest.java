package com.mycom.petcoupon.global.auth.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record AdminSessionCreateRequest(

		@NotBlank(message = "인증 코드는 필수입니다.")
		String authCode
) {
}
