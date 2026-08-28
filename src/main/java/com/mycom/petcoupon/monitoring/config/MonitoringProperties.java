package com.mycom.petcoupon.monitoring.config;

import java.time.Duration;
import java.util.List;

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

    /**
     * <b>구독 하나당</b> 버퍼링할 이벤트 수.
     *
     * <p>연결마다 큐를 따로 두므로 이 값은 전체 상한이 아니라 관리자 한 명분이다. 느린
     * 클라이언트의 큐가 차면 그 연결만 이벤트를 놓치고 나머지는 영향받지 않는다.
     */
    private int queueCapacity = 1_000;
    private Duration emitterTimeout = Duration.ofMinutes(30);

    /**
     * 동시에 열어 둘 수 있는 스트림 연결 수.
     *
     * <p>연결마다 큐 배열({@link #queueCapacity} 크기)과 가상 스레드를 하나씩 쓰므로 무제한이면
     * 연결을 닫지 않는 프론트 버그 하나가 메모리를 계속 갉아먹는다. 실제로 이 화면을 동시에 보는
     * 관리자는 한 자릿수라 넉넉히 잡아도 충분하다.
     *
     * <p>0 이하로 두면 제한하지 않는다.
     */
    private int maxSubscriptions = 50;

    /**
     * 관리자 화면에 올리지 않을 로거 이름 접두사.
     *
     * <p>root logger에 붙기 때문에 프레임워크 내부 WARN/ERROR까지 전부 지나간다. 그렇다고
     * {@code com.mycom.petcoupon}만 남기는 허용 목록으로 가면 안 된다 — Hibernate의 JDBC 오류,
     * Lettuce의 Redis 연결 끊김, Netty의 스레드풀 포화처럼 <b>정작 관리자가 봐야 하는 장애</b>가
     * 대부분 라이브러리 로거에서 나오기 때문이다. 그래서 허용이 아니라 제외로 간다.
     *
     * <p>기본값은 기동 시점에만 나오고 운영상 의미가 없는 것만 담는다. 실제 전체 테스트에서
     * WARN 1위(66건)가 BeanPostProcessorChecker였다.
     */
    private List<String> excludedLoggers = List.of(
            "org.springframework.context.support.PostProcessorRegistrationDelegate"
    );

    /**
     * 이벤트가 없는 동안 연결을 살려 두기 위해 보내는 heartbeat 간격.
     *
     * <p>WARN/ERROR는 평시에 몇 분씩 발생하지 않는 게 정상이라, 아무것도 안 보내면 그 사이
     * 리버스 프록시가 유휴 연결로 보고 끊는다. nginx {@code proxy_read_timeout}과 ALB idle
     * timeout이 둘 다 기본 60초이므로 그보다 충분히 짧아야 한다.
     *
     * <p>0 이하로 두면 heartbeat를 보내지 않는다. 프록시가 없는 로컬 개발에서 불필요한
     * 주기 작업을 끄는 용도다.
     */
    private Duration heartbeatInterval = Duration.ofSeconds(15);
}
