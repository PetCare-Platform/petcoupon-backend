package com.mycom.petcoupon.global.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.global.auth.annotation.NoAdminSession;
import com.mycom.petcoupon.global.auth.dto.req.AdminSessionCreateRequest;
import com.mycom.petcoupon.global.auth.dto.res.AdminSessionCreateResponse;
import com.mycom.petcoupon.global.auth.interceptor.AdminSessionInterceptor;
import com.mycom.petcoupon.global.auth.service.AdminSessionService;
import com.mycom.petcoupon.global.common.CustomResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/auth/sessions")
@RequiredArgsConstructor
public class AdminAuthController {

	private final AdminSessionService adminSessionService;

	// 세션을 받으려면 세션이 필요한 순환을 피하기 위해 이 메서드만 검증에서 제외한다.
	@NoAdminSession
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CustomResponse<AdminSessionCreateResponse> createSession(
			@Valid @RequestBody AdminSessionCreateRequest request
	) {
		AdminSessionCreateResponse response = adminSessionService.issue(request.authCode());

		return CustomResponse.onSuccess(HttpStatus.CREATED, response);
	}

	// 여기는 @NoAdminSession을 붙이지 않는다. 인터셉터가 먼저 토큰을 검증하므로
	// 유효한 세션만 자기 자신을 폐기할 수 있다.
	@DeleteMapping
	public CustomResponse<Void> deleteSession(
			@RequestHeader(AdminSessionInterceptor.HEADER) String token
	) {
		adminSessionService.revoke(token);

		return CustomResponse.onSuccess(null);
	}
}
