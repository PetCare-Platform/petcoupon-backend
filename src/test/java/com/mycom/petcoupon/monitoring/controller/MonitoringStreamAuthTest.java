package com.mycom.petcoupon.monitoring.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mycom.petcoupon.global.auth.interceptor.AdminSessionInterceptor;
import com.mycom.petcoupon.global.auth.service.AdminSessionService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;
import com.mycom.petcoupon.monitoring.exception.MonitoringErrorCode;
import com.mycom.petcoupon.monitoring.service.MonitoringSseService;

/**
 * SSE 스트림을 fetch 기반 클라이언트(@microsoft/fetch-event-source)로 호출하는 경로 검증.
 *
 * <p>이 클라이언트는 항상 {@code Accept: text/event-stream}을 보낸다. 그 헤더가 붙은 상태에서
 * 인증 실패가 어떻게 나가는지가 핵심이다 — 오류 응답이 Accept 협상에 걸리면 401이 아니라 500이
 * 나가고, 그러면 프론트가 만료된 세션으로 무한 재연결한다.
 *
 * <p>운영과 같게 {@link GlobalExceptionHandler}도 advice로 등록해 둔다. 전역 핸들러는 Content-Type을
 * 지정하지 않으므로, 아래 테스트가 통과한다는 건 {@code MonitoringController} 안의
 * {@code @ExceptionHandler}가 advice보다 먼저 선택돼 처리했다는 뜻이다.
 */
class MonitoringStreamAuthTest {

    private static final String STREAM_PATH = "/admin/monitoring/stream";

    private AdminSessionService adminSessionService;
    private MonitoringSseService monitoringSseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        adminSessionService = mock(AdminSessionService.class);
        monitoringSseService = mock(MonitoringSseService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new MonitoringController(monitoringSseService))
                .addMappedInterceptors(
                        new String[] {"/admin/**"},
                        new AdminSessionInterceptor(adminSessionService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Accept: text/event-stream이어도 인증 실패는 401 JSON으로 응답한다")
    void returnsJsonUnauthorizedEvenWhenClientAcceptsOnlyEventStream() throws Exception {
        when(adminSessionService.isValid(any())).thenReturn(false);

        // Accept 협상에 걸려 원래 예외가 다시 던져지면 여기서 500이 나온다.
        mockMvc.perform(get(STREAM_PATH).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401-0"));
    }

    @Test
    @DisplayName("연결 한도 초과는 503 JSON으로 응답한다")
    void returnsServiceUnavailableWhenConnectionLimitExceeded() throws Exception {
        when(adminSessionService.isValid("valid-token")).thenReturn(true);
        when(monitoringSseService.connect())
                .thenThrow(new GeneralException(MonitoringErrorCode.TOO_MANY_STREAM_CONNECTIONS));

        // 인증 실패와 마찬가지로 Accept 협상에 걸리지 않고 그대로 나가야 한다.
        mockMvc.perform(get(STREAM_PATH)
                        .header(AdminSessionInterceptor.HEADER, "valid-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("MONITORING503-0"));
    }

    @Test
    @DisplayName("허용되지 않는 메서드도 405 JSON으로 응답한다")
    void returnsMethodNotAllowedAsJson() throws Exception {
        when(adminSessionService.isValid("valid-token")).thenReturn(true);

        mockMvc.perform(post(STREAM_PATH)
                        .header(AdminSessionInterceptor.HEADER, "valid-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("COMMON405-0"));
    }

    @Test
    @DisplayName("예기치 못한 예외도 500 JSON으로 응답한다")
    void returnsInternalServerErrorAsJson() throws Exception {
        when(adminSessionService.isValid("valid-token")).thenReturn(true);
        when(monitoringSseService.connect()).thenThrow(new IllegalStateException("boom"));

        /*
         * catch-all 핸들러는 "무슨 일이 나든 깔끔한 JSON 500을 준다"는 안전망이다. 정작 그 안전망이
         * Accept 협상에 걸려 실패하면 프론트는 아무 단서도 못 받는다.
         */
        mockMvc.perform(get(STREAM_PATH)
                        .header(AdminSessionInterceptor.HEADER, "valid-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("COMMON500-0"));
    }

    @Test
    @DisplayName("X-ADMIN-KEY 헤더를 보내면 스트림이 열린다")
    void opensStreamWhenAdminKeyHeaderIsPresent() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(adminSessionService.isValid("valid-token")).thenReturn(true);
        when(monitoringSseService.connect()).thenReturn(emitter);

        // fetch 기반 클라이언트가 커스텀 헤더를 보낼 수 있다는 전제가 이 테스트다.
        mockMvc.perform(get(STREAM_PATH)
                        .header(AdminSessionInterceptor.HEADER, "valid-token")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"));

        emitter.complete();
    }
}
