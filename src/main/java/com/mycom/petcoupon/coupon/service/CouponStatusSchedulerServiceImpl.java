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
    // (자세한 이유는 CouponStatusSchedulerRegistrar 클래스 코멘트 참고).
    //
    // 여기서는 예외를 잡지 않는다. 스케줄러가 예외로 영구 정지하는 걸 막는 건 Registrar가
    // Runnable 자체를 감싸서 처리한다 — 이 메서드 안에서 잡으면 @Transactional 경계
    // (커넥션 획득 / 커밋)에서 난 예외는 애초에 잡히지 않을뿐더러, 여기서 예외를 삼키면
    // 트랜잭션이 rollback-only로 마킹돼 커밋 시점에 UnexpectedRollbackException이 새로
    // 터져 나온다. 그냥 던져서 트랜잭션을 깨끗이 롤백시키고, 다음 주기에 재시도하게 둔다
    // (세 전이 모두 조건부 UPDATE라 재시도가 안전하다).
    @Override
    @Transactional
    public void transitionCouponStatuses() {
        LocalDateTime now = LocalDateTime.now();

        // 순서가 중요하다. READY -> ACTIVE를 먼저 끝내야 이번에 막 ACTIVE가 된 쿠폰이 같은 실행에서
        // 바로 다음 상태로 넘어가지 않는다. soldOutCoupons를 endCoupons보다 앞에 둔 건, 발급 기간이
        // 끝났고 재고도 0인 쿠폰이 ACTIVE -> SOLD_OUT -> ENDED를 같은 실행 안에서 거쳐 곧장 ENDED에
        // 도달하게 하기 위함이다(순서를 바꿔도 최종 상태는 같지만, endCoupons가 SOLD_OUT을 함께
        // 잡으므로 이 순서면 그 쿠폰은 endCoupons에서 한 번에 끝난다).
        int activated = couponRepository.activateCoupons(now);
        int soldOut = couponRepository.soldOutCoupons(now);
        int ended = couponRepository.endCoupons(now);

        if (activated > 0 || soldOut > 0 || ended > 0) {
            log.info("쿠폰 상태 전이 완료. activated={}건, soldOut={}건, ended={}건", activated, soldOut, ended);
        }
    }
}
