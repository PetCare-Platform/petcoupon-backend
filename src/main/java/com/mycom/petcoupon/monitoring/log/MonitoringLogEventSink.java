package com.mycom.petcoupon.monitoring.log;

import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;

/**
 * Logback appender가 Spring monitoring 계층으로 이벤트를 넘기는 매우 작은 경계다.
 */
public interface MonitoringLogEventSink {

    /**
     * 이 이벤트를 만들 가치가 있는지 알려준다.
     *
     * <p>{@link MonitoringLogEventMapper#from}은 UUID 생성과 정규식 마스킹을 포함해서
     * 공짜가 아닌데, 그 비용은 로그를 남긴 요청 스레드가 낸다. 스트림이 꺼져 있거나 보고 있는
     * 관리자가 없으면 어차피 버려질 이벤트이므로, 만들기 전에 여기서 먼저 걸러낸다.
     *
     * <p>이 값은 확인한 다음 순간 바뀔 수 있다. 그래서 {@link #offer}는 이 체크를 신뢰하지 않고
     * 자체적으로 다시 확인한다 — 여기서 통과한 이벤트가 뒤늦게 버려지는 건 정상이다.
     */
    boolean isAcceptingEvents();

    void offer(MonitoringEventResponse event);
}
