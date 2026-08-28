package com.mycom.petcoupon.monitoring.log;

import com.mycom.petcoupon.monitoring.dto.res.MonitoringEventResponse;

/**
 * Logback appender가 Spring monitoring 계층으로 이벤트를 넘기는 매우 작은 경계다.
 */
public interface MonitoringLogEventSink {

    void offer(MonitoringEventResponse event);
}
