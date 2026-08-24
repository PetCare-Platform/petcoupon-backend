package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.repository.CouponRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponStatusSchedulerServiceImpl implements CouponStatusSchedulerService {

    private final CouponRepository couponRepository;

    // 실행 스케줄 등록은 @Scheduled 대신 CouponStatusSchedulerRegistrar가 명시적으로 한다
    // (자세한 이유는 CouponStatusSchedulerRegistrar 클래스 코멘트 참고). 그 Registrar는 순수
    // ScheduledExecutorService.scheduleAtFixedRate를 쓰는데, 이건 태스크가 예외를 한 번이라도
    // 던지면 이후 실행을 영구히 멈춰버린다(JDK 문서에 명시된 동작) — Spring @Scheduled처럼
    // 예외를 알아서 잡아주지 않으므로, 스케줄러가 계속 살아있으려면 여기서 직접 잡아야 한다.
    @Override
    @Transactional
    public void transitionCouponStatuses() {
        LocalDateTime now = LocalDateTime.now();

        try {
            // READY -> ACTIVE 를 먼저 끝내야, 이번에 막 ACTIVE가 된 쿠폰이 같은 실행에서 바로
            // ENDED로 넘어가지 않는다(activateCoupons는 issueEndAt이 이미 지난 건 애초에 건드리지 않음).
            int activated = couponRepository.activateCoupons(now);
            int ended = couponRepository.endCoupons(now);

            if (activated > 0 || ended > 0) {
                log.info("쿠폰 상태 전이 완료. activated={}건, ended={}건", activated, ended);
            }
        } catch (Exception e) {
            log.error("쿠폰 상태 전이 스케줄러 실행 중 오류가 발생했습니다.", e);
        }
    }
}
