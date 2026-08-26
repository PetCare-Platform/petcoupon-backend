package com.mycom.petcoupon.global.auth.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.global.auth.annotation.NoAdminSession;
import com.mycom.petcoupon.global.auth.service.AdminSessionService;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

/**
 * 일반 관리자 엔드포인트가 실제로 막히는지 확인한다. AdminAuthControllerTest는
 * 인증 API 자신만 다루므로, /admin/** 전반이 닫혀 있다는 보장은 여기서 만든다.
 */
@ExtendWith(MockitoExtension.class)
class AdminSessionInterceptorTest {

	private static final String VALID_TOKEN = "valid-token";

	@Mock
	private AdminSessionService adminSessionService;

	private MockMvc mockMvc;

	@RestController
	static class StubAdminController {

		@GetMapping("/admin/stub")
		String protectedEndpoint() {
			return "ok";
		}

		@NoAdminSession
		@GetMapping("/admin/stub/open")
		String openEndpoint() {
			return "ok";
		}
	}

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new StubAdminController())
				.addMappedInterceptors(
						new String[] {"/admin/**"},
						new AdminSessionInterceptor(adminSessionService)
				)
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void blocksAdminEndpointWhenHeaderIsMissing() throws Exception {
		mockMvc.perform(get("/admin/stub"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON401-0"));
	}

	@Test
	void blocksAdminEndpointWhenTokenIsInvalid() throws Exception {
		when(adminSessionService.isValid("bogus")).thenReturn(false);

		mockMvc.perform(get("/admin/stub")
					.header(AdminSessionInterceptor.HEADER, "bogus"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON401-0"));
	}

	@Test
	void allowsAdminEndpointWhenTokenIsValid() throws Exception {
		when(adminSessionService.isValid(VALID_TOKEN)).thenReturn(true);

		mockMvc.perform(get("/admin/stub")
					.header(AdminSessionInterceptor.HEADER, VALID_TOKEN))
				.andExpect(status().isOk());
	}

	@Test
	void allowsEndpointAnnotatedWithNoAdminSession() throws Exception {
		mockMvc.perform(get("/admin/stub/open"))
				.andExpect(status().isOk());

		verify(adminSessionService, never()).isValid(any());
	}

	// 실패 응답이 전부 같아야 토큰의 존재 여부가 드러나지 않는다.
	// isValid는 스텁하지 않는다 — 미등록 토큰의 기본 반환이 false라 실제 상황과 같고,
	// 스텁하면 헤더 없는 요청의 isValid(null) 호출과 인자가 어긋나 strict stubs에 걸린다.
	@Test
	void returnsSameResponseForMissingAndInvalidToken() throws Exception {
		String missing = mockMvc.perform(get("/admin/stub"))
				.andReturn().getResponse().getContentAsString();

		String invalid = mockMvc.perform(get("/admin/stub")
					.header(AdminSessionInterceptor.HEADER, "bogus"))
				.andReturn().getResponse().getContentAsString();

		assertThat(missing).isEqualTo(invalid);
	}
}
