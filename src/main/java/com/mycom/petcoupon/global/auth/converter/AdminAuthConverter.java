package com.mycom.petcoupon.global.auth.converter;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.global.auth.dto.res.AdminSessionCreateResponse;

/**
 * 관리자 인증 응답 변환을 담당한다.
 *
 * 세션은 DB 엔티티가 아니라 Redis에만 존재해서 다른 도메인의 Converter처럼
 * Entity를 받지는 않는다. 그래도 응답 조립을 서비스 밖으로 빼두면 응답 필드가
 * 늘어날 때 서비스 로직을 건드리지 않아도 되고, 변환 위치도 다른 도메인과 같아진다.
 */
@Component
public class AdminAuthConverter {

	public AdminSessionCreateResponse toCreateResponse(String token, LocalDateTime expiresAt) {
		return AdminSessionCreateResponse.builder()
				.token(token)
				.expiresAt(expiresAt)
				.build();
	}
}
