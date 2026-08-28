package com.mycom.petcoupon.monitoring.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
