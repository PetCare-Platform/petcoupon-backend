package com.mycom.petcoupon.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.mycom.petcoupon.monitoring.config.MonitoringProperties;
import com.mycom.petcoupon.monitoring.controller.MonitoringController;
import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;

/**
 * heartbeat 동작 검증.
 *
 * <p>SseEmitter는 servlet 응답에 initialize된 뒤에야 실제로 바이트를 쓴다. Spring의
 * {@code ResponseBodyEmitter.Handler}는 package-private이라 테스트에서 직접 만들 수 없으므로,
 * MockMvc로 컨트롤러를 태워 emitter를 초기화시키고 MockHttpServletResponse에 쌓인 raw SSE
 * 프레임을 읽는다. 즉 여기서 검증하는 건 프론트가 실제로 받게 될 wire format이다.
 */
class MonitoringSseHeartbeatTest {

    private static final String HEARTBEAT_FRAME = "event:heartbeat";
    private static final String MONITORING_FRAME = "event:monitoring-event";

    private MonitoringSseService service;
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    @DisplayName("이벤트가 없어도 heartbeat가 주기적으로 전송된다")
    void sendsHeartbeatWhileIdle() throws Exception {
        startWith(Duration.ofMillis(100));
        MvcResult stream = connect();

        // 유휴 상태에서 아무 이벤트도 넣지 않아도 heartbeat만으로 연결이 유지되어야 한다.
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(occurrences(bodyOf(stream), HEARTBEAT_FRAME))
                        .isGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("이벤트가 계속 흐르는 동안에는 heartbeat를 덧붙이지 않는다")
    void skipsHeartbeatWhileEventsFlow() throws Exception {
        startWith(Duration.ofSeconds(1));
        MvcResult stream = connect();

        // heartbeat 간격(1초)보다 훨씬 촘촘하게 흘려보내면 유휴 구간이 생기지 않는다.
        for (int i = 0; i < 10; i++) {
            service.offer(event("발급 실패 " + i));
            Thread.sleep(100);
        }

        String body = bodyOf(stream);
        assertThat(occurrences(body, MONITORING_FRAME)).isEqualTo(10);
        assertThat(body).doesNotContain(HEARTBEAT_FRAME);
    }

    @Test
    @DisplayName("heartbeat 간격이 0이면 heartbeat 없이 이벤트만 전달된다")
    void disablesHeartbeatWhenIntervalIsZero() throws Exception {
        startWith(Duration.ZERO);
        MvcResult stream = connect();

        service.offer(event("발급 실패"));

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(bodyOf(stream)).contains(MONITORING_FRAME));
        assertThat(bodyOf(stream)).doesNotContain(HEARTBEAT_FRAME);
    }

    @Test
    @DisplayName("스트림이 꺼져 있어도 heartbeat는 계속 보내 연결을 유지한다")
    void keepsHeartbeatingWhileStreamDisabled() throws Exception {
        startWith(Duration.ofMillis(100));
        service.setStreamEnabled(false);

        MvcResult stream = connect();

        /*
         * OFF여도 연결을 닫지 않으므로(재연결 폭주 방지) heartbeat도 계속 나가야 한다.
         * 안 보내면 프록시가 유휴 연결로 보고 끊고, 그러면 결국 재연결이 다시 시작된다.
         */
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(occurrences(bodyOf(stream), HEARTBEAT_FRAME))
                        .isGreaterThanOrEqualTo(2));

        // 꺼진 상태이므로 connected는 false로 알리고, 실제 이벤트는 나가지 않는다.
        assertThat(bodyOf(stream)).contains("event:connected");
        assertThat(bodyOf(stream)).doesNotContain(MONITORING_FRAME);
    }

    private void startWith(Duration heartbeatInterval) {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setQueueCapacity(100);
        properties.setEmitterTimeout(Duration.ofMinutes(1));
        properties.setHeartbeatInterval(heartbeatInterval);

        service = new MonitoringSseService(properties, new SimpleMeterRegistry());
        mockMvc = MockMvcBuilders.standaloneSetup(new MonitoringController(service)).build();
    }

    private MvcResult connect() throws Exception {
        return mockMvc.perform(get("/admin/monitoring/stream")).andReturn();
    }

    /*
     * MockHttpServletResponse는 내부적으로 ByteArrayOutputStream이고 write/toString이 모두
     * synchronized라, 워커 스레드가 쓰는 중에 읽어도 안전하다.
     */
    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private int occurrences(String body, String frame) {
        int count = 0;
        int index = body.indexOf(frame);

        while (index >= 0) {
            count++;
            index = body.indexOf(frame, index + frame.length());
        }

        return count;
    }

    private MonitoringEventResponse event(String message) {
        return new MonitoringEventResponse(
                UUID.randomUUID().toString(),
                "ERROR",
                "CouponIssueStreamConsumer",
                message,
                "IllegalStateException",
                LocalDateTime.now()
        );
    }
}
