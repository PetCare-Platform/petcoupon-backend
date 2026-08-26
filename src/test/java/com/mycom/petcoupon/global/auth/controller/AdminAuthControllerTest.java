package com.mycom.petcoupon.global.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.global.auth.dto.res.AdminSessionCreateResponse;
import com.mycom.petcoupon.global.auth.interceptor.AdminSessionInterceptor;
import com.mycom.petcoupon.global.auth.service.AdminSessionService;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

/**
 * 인터셉터를 함께 태워서 검증한다. 발급은 열려 있어야 하고 폐기는 막혀 있어야 하는데
 * 그 구분이 인터셉터 안에서 일어나므로, 컨트롤러만 단독으로 세워서는 확인할 수 없다.
 */
@ExtendWith(MockitoExtension.class)
class AdminAuthControllerTest {

	private static final String VALID_TOKEN = "valid-token";

	@Mock
	private AdminSessionService adminSessionService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new AdminAuthController(adminSessionService))
				.addMappedInterceptors(
						new String[] {"/admin/**"},
						new AdminSessionInterceptor(adminSessionService)
				)
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	// @NoAdminSession이 붙어 있어 토큰 없이도 통과해야 한다. 막히면 세션을 받으려면
	// 세션이 필요한 순환에 빠진다.
	@Test
	void createSessionIsReachableWithoutToken() throws Exception {
		when(adminSessionService.issue("right-code")).thenReturn(
				AdminSessionCreateResponse.builder()
						.token(VALID_TOKEN)
						.expiresAt(LocalDateTime.of(2026, 8, 26, 9, 0))
						.build()
		);

		mockMvc.perform(post("/admin/auth/sessions")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "authCode": "right-code"
							}
							"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.token").value(VALID_TOKEN));
	}

	@Test
	void createSessionReturnsUnauthorizedWhenAuthCodeIsWrong() throws Exception {
		when(adminSessionService.issue("wrong-code"))
				.thenThrow(new GeneralException(CommonErrorCode.UNAUTHORIZED));

		mockMvc.perform(post("/admin/auth/sessions")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "authCode": "wrong-code"
							}
							"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON401-0"));
	}

	@Test
	void createSessionReturnsValidationErrorWhenAuthCodeIsBlank() throws Exception {
		mockMvc.perform(post("/admin/auth/sessions")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "authCode": "   "
							}
							"""))
				.andExpect(status().isBadRequest());

		verify(adminSessionService, never()).issue(any());
	}

	// 폐기에는 @NoAdminSession이 없으므로 인터셉터가 먼저 막아야 한다.
	// 여기가 열려 있으면 아무나 남의 세션을 끊을 수 있다.
	@Test
	void deleteSessionIsBlockedWithoutToken() throws Exception {
		mockMvc.perform(delete("/admin/auth/sessions"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON401-0"));

		verify(adminSessionService, never()).revoke(any());
	}

	@Test
	void deleteSessionIsBlockedWhenTokenIsInvalid() throws Exception {
		when(adminSessionService.isValid("bogus")).thenReturn(false);

		mockMvc.perform(delete("/admin/auth/sessions")
					.header(AdminSessionInterceptor.HEADER, "bogus"))
				.andExpect(status().isUnauthorized());

		verify(adminSessionService, never()).revoke(any());
	}

	@Test
	void deleteSessionRevokesWhenTokenIsValid() throws Exception {
		when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

		mockMvc.perform(delete("/admin/auth/sessions")
					.header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true));

		verify(adminSessionService).revoke(VALID_TOKEN);
	}
}
