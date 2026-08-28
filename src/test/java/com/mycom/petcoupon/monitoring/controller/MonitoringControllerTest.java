package com.mycom.petcoupon.monitoring.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mycom.petcoupon.monitoring.service.MonitoringSseService;

class MonitoringControllerTest {

    private MonitoringSseService monitoringSseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        monitoringSseService = mock(MonitoringSseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MonitoringController(monitoringSseService)).build();
    }

    @Test
    @DisplayName("스트림 응답에 프록시 버퍼링/캐시 방지 헤더가 붙는다")
    void streamDeclaresProxyBufferingHeaders() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(monitoringSseService.connect()).thenReturn(emitter);

        // nginx가 proxy_buffering을 켜 둔 채로 앞단에 있으면 이벤트가 고였다 한꺼번에 나가고
        // heartbeat도 프록시까지 도달하지 못한다. 이 헤더가 빠지면 실시간성이 조용히 깨진다.
        mockMvc.perform(get("/admin/monitoring/stream"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        emitter.complete();
    }

    @Test
    void returnsCurrentStreamingSetting() throws Exception {
        when(monitoringSseService.isStreamEnabled()).thenReturn(true);

        mockMvc.perform(get("/admin/monitoring/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.streamEnabled").value(true));
    }

    @Test
    void changesStreamingSetting() throws Exception {
        when(monitoringSseService.isStreamEnabled()).thenReturn(false);

        mockMvc.perform(patch("/admin/monitoring/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"streamEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.streamEnabled").value(false));

        verify(monitoringSseService).setStreamEnabled(false);
    }
}
