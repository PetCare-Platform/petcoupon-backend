package com.mycom.petcoupon.global.auth.dto.res;

import java.time.LocalDateTime;

import lombok.Builder;

@Builder
public record AdminSessionCreateResponse(
		String token,
		LocalDateTime expiresAt
) {
}
