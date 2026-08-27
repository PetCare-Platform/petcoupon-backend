package com.mycom.petcoupon.system.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;
import com.mycom.petcoupon.system.converter.SystemHealthConverter;
import com.mycom.petcoupon.system.dto.res.ComponentHealthResponse;
import com.mycom.petcoupon.system.dto.res.SystemHealthResponse;
import com.mycom.petcoupon.system.service.SystemHealthService;
import com.mycom.petcoupon.system.service.SystemHealthService.SystemHealthSnapshot;

@ExtendWith(MockitoExtension.class)
class SystemHealthControllerTest {

    @Mock
    private SystemHealthService systemHealthService;

    @Mock
    private SystemHealthConverter systemHealthConverter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SystemHealthController(systemHealthService, systemHealthConverter))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getHealthReturnsOverallStatusAndComponents() throws Exception {
        SystemHealthSnapshot snapshot = mock(SystemHealthSnapshot.class);
        SystemHealthResponse response = SystemHealthResponse.builder()
                .overallStatus("UP")
                .components(List.of(
                        ComponentHealthResponse.builder().name("db").status("UP").build(),
                        ComponentHealthResponse.builder().name("redis").status("UP").build()
                ))
                .build();

        when(systemHealthService.getSnapshot()).thenReturn(snapshot);
        when(systemHealthConverter.toResponse(snapshot)).thenReturn(response);

        mockMvc.perform(get("/admin/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.overallStatus").value("UP"))
                .andExpect(jsonPath("$.result.components[0].name").value("db"))
                .andExpect(jsonPath("$.result.components[0].status").value("UP"))
                .andExpect(jsonPath("$.result.components[1].name").value("redis"));
    }
}
