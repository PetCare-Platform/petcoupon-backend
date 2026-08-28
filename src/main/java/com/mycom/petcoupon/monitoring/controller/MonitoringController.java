package com.mycom.petcoupon.monitoring.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.monitoring.dto.req.MonitoringSettingsUpdateRequest;
import com.mycom.petcoupon.monitoring.dto.res.MonitoringSettingsResponse;
import com.mycom.petcoupon.monitoring.service.MonitoringSseService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    /*
     * nginx는 기본적으로 upstream 응답을 버퍼링한다(proxy_buffering on). SSE에 이게 걸리면
     * 이벤트가 버퍼에 고였다가 한꺼번에 나가서 "실시간"이 아니게 되고, heartbeat도 프록시 앞단에
     * 도달하지 못해 유휴 연결 차단을 막지 못한다. 이 헤더는 해당 응답에 한해 버퍼링을 끈다.
     *
     * 표준 헤더가 아니라 nginx 계열(및 이를 따르는 일부 ingress) 확장이다. 모르는 프록시는 그냥
     * 무시하므로 켜 두는 쪽이 안전하다.
     */
    private static final String NGINX_BUFFERING_HEADER = "X-Accel-Buffering";

    private final MonitoringSseService monitoringSseService;

    /*
     * [클라이언트 계약] 이 엔드포인트는 브라우저 네이티브 EventSource로 호출할 수 없다.
     *
     * /admin/**은 AdminSessionInterceptor가 X-ADMIN-KEY 헤더로 인증하는데, EventSource는
     * 커스텀 헤더를 설정하는 API 자체가 없다. 세션 키를 query parameter로 옮기는 방법도 있지만
     * 액세스 로그·Referer·프록시 로그에 8시간짜리 토큰이 그대로 남으므로 택하지 않았다.
     *
     * 따라서 프론트는 @microsoft/fetch-event-source 같은 fetch 기반 SSE 클라이언트를 쓴다.
     * fetch는 커스텀 헤더를 보낼 수 있으므로 다른 /admin/** 호출과 인증 방식이 완전히 같다.
     *
     *   await fetchEventSource('/admin/monitoring/stream', {
     *       headers: { 'X-ADMIN-KEY': token },
     *       onopen: (res) => { if (res.status === 401) throw new FatalError(); },
     *       onmessage: (ev) => { ... },   // ev.event: connected | monitoring-event | heartbeat
     *   });
     *
     * 프론트 쪽에서 반드시 지켜야 하는 것 두 가지:
     *
     *  1. 401은 재시도 대상이 아니다. fetch-event-source는 기본적으로 모든 실패를 재시도하므로,
     *     관리자 세션(기본 8시간)이 만료되면 onopen에서 fatal로 끊고 재로그인시켜야 한다.
     *     안 그러면 만료된 키로 무한 재연결한다.
     *  2. 재연결은 이어받기가 아니라 처음부터다. 이 스트림은 SSE id 필드를 쓰지 않고 서버에
     *     재전송 버퍼도 없어서, Last-Event-ID를 보내도 끊긴 동안의 이벤트는 복구되지 않는다.
     *     실시간 관측용이라 의도한 것이고, 유실이 곤란한 값은 통계 API로 따로 조회한다.
     *
     * cross-origin 배포라면 커스텀 헤더 때문에 preflight(OPTIONS)가 생긴다. 현재 프로젝트에는
     * CORS 설정이 없으므로 same-origin 서빙이 전제다.
     */

    /*
     * SseEmitter를 그대로 반환하지 않고 ResponseEntity로 감싸는 이유는 위 버퍼링/캐시 헤더를
     * 명시하기 위해서다. Spring의 ResponseBodyEmitterReturnValueHandler가 ResponseEntity의
     * 상태·헤더를 먼저 응답에 적용한 뒤 본문 emitter를 비동기로 처리하므로, 스트리밍 동작은
     * 그대로 유지된다.
     *
     * Connection: keep-alive는 넣지 않는다. HTTP/1.1에서는 컨테이너가 알아서 관리하고,
     * HTTP/2에서는 hop-by-hop 헤더가 금지라 오히려 응답이 거부될 수 있다.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(NGINX_BUFFERING_HEADER, "no")
                .body(monitoringSseService.connect());
    }

    @GetMapping("/settings")
    public CustomResponse<MonitoringSettingsResponse> getSettings() {
        return CustomResponse.onSuccess(new MonitoringSettingsResponse(monitoringSseService.isStreamEnabled()));
    }

    @PatchMapping("/settings")
    public CustomResponse<MonitoringSettingsResponse> updateSettings(
            @Valid @RequestBody MonitoringSettingsUpdateRequest request
    ) {
        monitoringSseService.setStreamEnabled(request.streamEnabled());
        return CustomResponse.onSuccess(new MonitoringSettingsResponse(monitoringSseService.isStreamEnabled()));
    }
}
