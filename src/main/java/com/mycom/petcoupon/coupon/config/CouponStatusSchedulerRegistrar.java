package com.mycom.petcoupon.coupon.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.service.CouponStatusSchedulerService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * CouponStatusSchedulerService.transitionCouponStatuses()를 이 클래스가 직접 만드는
 * 단일 스레드 ScheduledExecutorService로 돌린다. Spring TaskScheduler 빈을 전혀 쓰지 않으므로
 * (다른 빈에 노출되지 않는 private 필드) 다른 브랜치가 TaskScheduler 빈을 몇 개를 추가하든,
 * 이름을 뭘로 짓든 절대 충돌하지 않는다 — coupon 패키지 밖 파일은 아예 볼 일이 없다.
 */
@Component
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
                couponStatusSchedulerService::transitionCouponStatuses,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
