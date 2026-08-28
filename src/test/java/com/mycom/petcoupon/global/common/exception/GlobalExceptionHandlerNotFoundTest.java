package com.mycom.petcoupon.global.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 매칭되는 핸들러가 없는 경로가 404로 나가는지 지킨다(#157).
 *
 * 원래는 GlobalExceptionHandler의 @ExceptionHandler(Exception.class)가
 * NoResourceFoundException까지 잡아서 전부 500으로 나갔다. 그러면 오타 URL과
 * 서버 장애가 응답으로 구분되지 않는다.
 *
 * @SpringBootTest로 띄우는 이유 — NoResourceFoundException은 Boot가 /** 에 걸어두는
 * ResourceHttpRequestHandler가 던진다. 순수 @EnableWebMvc 컨텍스트(CouponControllerTest 방식)에는
 * 그 핸들러가 없어서 NoHandlerFoundException이 대신 나오고, 그러면 운영과 다른 경로를 검증하게 된다.
 *
 * 실행 전 MySQL이 떠 있어야 한다: docker compose up -d mysql
 */
@SpringBootTest(properties = {
		// 이 테스트는 발급 파이프라인과 무관하다. 컨슈머·스케줄러가 뜨면 검증과 상관없는
		// 부수효과(Outbox 발행, 이벤트 상태 전이)가 생기므로 전부 꺼둔다.
		"coupon.issue.stream.enabled=false",
		"coupon.issue.outbox.enabled=false",
		"event.status.scheduler.enabled=false",
		"coupon.status.enabled=false",
		"spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
class GlobalExceptionHandlerNotFoundTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("매칭되지 않는 경로는 404와 COMMON404-0으로 응답한다")
	void unmappedPath_returnsNotFound() throws Exception {
		mockMvc.perform(get("/definitely-not-a-real-path"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COMMON404-0"))
				.andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("실재하는 경로 아래의 없는 하위 경로도 404로 응답한다")
	void unmappedSubPath_returnsNotFound() throws Exception {
		// /coupons 는 매핑이 있는 접두사다. 접두사가 겹쳐도 catch-all로 새지 않는지 본다.
		mockMvc.perform(post("/coupons/1/definitely-not-a-real-path")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMON404-0"));
	}

	@Test
	@DisplayName("SSE 클라이언트가 경로를 잘못 쳐도 404 JSON으로 응답한다")
	void unmappedPathWithEventStreamAccept_returnsNotFoundJson() throws Exception {
		/*
		 * fetch 기반 SSE 클라이언트는 Accept: text/event-stream만 보낸다. 오류 응답의
		 * Content-Type을 명시하지 않으면 이 헤더로 협상하다 실패해서, 404가 500으로 뭉개진다.
		 * 이 경로는 컨트롤러에 매핑되지 않으므로 컨트롤러 단위 advice로는 막을 수 없다 —
		 * GlobalExceptionHandler가 책임져야 하는 자리다.
		 */
		mockMvc.perform(get("/admin/monitorng/stream")
						.accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(status().isNotFound())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("COMMON404-0"));
	}
}
