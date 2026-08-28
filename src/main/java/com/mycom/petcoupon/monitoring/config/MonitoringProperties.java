package com.mycom.petcoupon.monitoring.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * 모니터링은 애플리케이션 관측 기능일 뿐이므로, 큐가 밀려도 호출 스레드를 기다리게 하지 않는다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "monitoring.sse")
public class MonitoringProperties {

    private int queueCapacity = 1_000;
    private Duration emitterTimeout = Duration.ofMinutes(30);
}
