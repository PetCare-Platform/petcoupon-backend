package com.mycom.petcoupon.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 프론트엔드 연동을 위한 WebConfig CORS 매핑 검증.
 */
@SpringBootTest(properties = {
		"coupon.issue.stream.enabled=false",
		"coupon.issue.outbox.enabled=false",
		"event.status.scheduler.enabled=false",
		"coupon.status.enabled=false",
		"spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
class WebConfigCorsTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("프론트엔드 Origin에 대해 Preflight(OPTIONS) 요청 시 CORS 헤더가 응답된다")
	void corsPreflight_returnsAllowedHeaders() throws Exception {
		mockMvc.perform(options("/events")
						.header(HttpHeaders.ORIGIN, "http://localhost:5173")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Idempotency-Key,Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
				.andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
				.andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));
	}
}
