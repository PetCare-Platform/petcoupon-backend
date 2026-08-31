package com.mycom.petcoupon.global.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * SSE 연결이 끊긴 뒤의 예외가 JSON 오류 응답을 다시 쓰지 않는지 지킨다(#191).
 *
 * <p>원래는 {@code AsyncRequestNotUsableException}이 catch-all(@ExceptionHandler(Exception.class))에
 * 잡혀 ERROR 로그 + JSON 500을 만들었고, 그 JSON을 이미 못 쓰는 응답에 쓰려다
 * {@code HttpMessageNotWritableException}이 2차로 터졌다. 관리자가 모니터링 탭을 닫을 때마다
 * 서버 장애처럼 보이는 로그 한 쌍이 남는 셈이다.
 *
 * <p>여기서 검증하는 건 "본문을 쓰지 않는다"는 것 하나다. 실제 write 실패는 MockMvc로
 * 재현할 수 없지만, 본문을 쓰지 않으면 그 실패 자체가 성립하지 않는다.
 */
class GlobalExceptionHandlerAsyncTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("끊긴 비동기 응답에는 JSON 오류 본문을 다시 쓰지 않는다")
	void writesNoBodyWhenAsyncResponseIsNotUsable() throws Exception {
		MvcResult result = mockMvc.perform(get("/test/async-not-usable")
						.accept(MediaType.TEXT_EVENT_STREAM))
				.andReturn();

		// 본문이 비어 있어야 한다. 여기서 JSON이 나가면 운영에서는 write 실패로 이어진다.
		assertThat(result.getResponse().getContentAsString()).isEmpty();

		// catch-all로 새지 않고 전용 핸들러가 처리했다는 뜻이다.
		assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
	}

	@Test
	@DisplayName("클라이언트가 먼저 끊은 경우도 500 본문을 쓰지 않는다")
	void writesNoBodyWhenClientDisconnected() throws Exception {
		// Tomcat ClientAbortException 계열은 타입이 달라도 원인이 같다 — catch-all이 걸러야 한다.
		MvcResult result = mockMvc.perform(get("/test/broken-pipe"))
				.andReturn();

		assertThat(result.getResponse().getContentAsString()).isEmpty();
	}

	@Test
	@DisplayName("커밋되지 않은 비동기 요청의 타임아웃은 503 JSON으로 응답한다")
	void returnsServiceUnavailableForUncommittedAsyncTimeout() throws Exception {
		// 스트리밍이 아닌 async 엔드포인트는 아직 아무것도 안 썼으므로 정상적으로 오류를 줄 수 있다.
		mockMvc.perform(get("/test/async-timeout"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("COMMON503-0"));
	}

	@Test
	@DisplayName("일반 예외는 그대로 500 JSON으로 응답한다")
	void stillReturnsInternalServerErrorForRealFailures() throws Exception {
		// 연결 끊김 처리를 넣느라 진짜 장애까지 조용히 삼키면 안 된다.
		mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON500-0"));
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/test/async-not-usable")
		String asyncNotUsable() throws IOException {
			throw new AsyncRequestNotUsableException("Response not usable after async request completion");
		}

		@GetMapping("/test/broken-pipe")
		String brokenPipe() throws IOException {
			throw new IOException("Broken pipe");
		}

		@GetMapping("/test/async-timeout")
		String asyncTimeout() {
			throw new AsyncRequestTimeoutException();
		}

		@GetMapping("/test/boom")
		String boom() {
			throw new IllegalStateException("진짜 장애");
		}
	}
}
