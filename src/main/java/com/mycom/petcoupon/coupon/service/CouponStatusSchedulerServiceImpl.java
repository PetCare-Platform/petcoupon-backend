package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
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

    @Override
    @Transactional
    @Scheduled(cron = "${coupon.status.cron:0 * * * * *}") // 기본 매 분 정각. 테스트에서 초 단위로 낮춰서 검증 가능
    public void transitionCouponStatuses() {
        LocalDateTime now = LocalDateTime.now();

        // READY -> ACTIVE 를 먼저 끝내야, 이번에 막 ACTIVE가 된 쿠폰이 같은 실행에서 바로
        // ENDED로 넘어가지 않는다(activateCoupons는 issueEndAt이 이미 지난 건 애초에 건드리지 않음).
        int activated = couponRepository.activateCoupons(now);
        int ended = couponRepository.endCoupons(now);

        log.info("쿠폰 상태 전이 완료. activated={}건, ended={}건", activated, ended);
    }
}
