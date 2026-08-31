package com.mycom.petcoupon.coupon.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.service.CouponStatusSchedulerService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * CouponStatusSchedulerService.transitionCouponStatuses()를 이 클래스가 직접 만드는
 * 단일 스레드 ScheduledExecutorService로 돌린다. Spring TaskScheduler 빈을 전혀 쓰지 않으므로
 * (다른 빈에 노출되지 않는 private 필드) 다른 브랜치가 TaskScheduler 빈을 몇 개를 추가하든,
 * 이름을 뭘로 짓든 절대 충돌하지 않는다 — coupon 패키지 밖 파일은 아예 볼 일이 없다.
 */
@Slf4j
@Component
// 앱 전체를 띄우는 테스트에서 이 스케줄러를 끌 수 있게 한다.
// 켜져 있으면 테스트가 도는 동안에도 쿠폰 상태를 READY -> ACTIVE로 바꿔서
// 다른 테스트가 잡아둔 전제를 깨뜨린다.
// 키를 coupon.status.enabled로 둔 건 짝이 되는
// coupon.status.interval-seconds와 접두사를 맞추기 위함이다.
// 기본값은 켜짐이므로 운영 동작은 그대로다.
@ConditionalOnProperty(
	prefix = "coupon.status",
	name = "enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class CouponStatusSchedulerRegistrar {

    private final CouponStatusSchedulerService couponStatusSchedulerService;
    private final long intervalSeconds;

    private ScheduledExecutorService scheduler;

    public CouponStatusSchedulerRegistrar(
            CouponStatusSchedulerService couponStatusSchedulerService,
            @Value("${coupon.status.interval-seconds:60}") long intervalSeconds // 기본 60초. 테스트에서 짧게 낮춰서 검증 가능
    ) {
        this.couponStatusSchedulerService = couponStatusSchedulerService;
        this.intervalSeconds = intervalSeconds;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "coupon-status-scheduler")
        );
        scheduler.scheduleAtFixedRate(
                this::runSafely,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    // scheduleAtFixedRate는 태스크가 예외를 한 번이라도 던지면 이후 실행을 영구히 멈춘다
    // (JDK 명세). Spring @Scheduled와 달리 알아서 잡아주는 계층이 없으므로 여기서 직접 막는다.
    //
    // 이 감싸기는 반드시 서비스 메서드 "바깥"에 있어야 한다. @Transactional 프록시는
    // 커넥션 획득 -> 메서드 본문 -> 커밋 순으로 도는데, 메서드 본문 안에서 잡으면 커넥션
    // 획득 실패(부하 시 커넥션 풀 고갈 등)나 커밋 실패는 본문 바깥에서 터지므로 잡히지 않고
    // 그대로 스케줄러를 죽인다. Error까지 포함해서 잡아야 어떤 경우에도 주기가 유지된다.
    private void runSafely() {
        try {
            couponStatusSchedulerService.transitionCouponStatuses();
        } catch (Throwable t) {
            log.error("쿠폰 상태 전이 스케줄러 실행 중 오류가 발생했습니다. 다음 주기에 재시도합니다.", t);
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
