package com.mycom.petcoupon.monitoring.dto.res;

import java.time.LocalDateTime;

/**
 * 관리자 화면에 전송하는 축약된 장애 이벤트다. stack trace와 원문 예외 메시지는 포함하지 않는다.
 */
public record MonitoringEventResponse(
        String id,
        String level,
        String source,
        String message,
        String exception,
        LocalDateTime occurredAt
) {
}
