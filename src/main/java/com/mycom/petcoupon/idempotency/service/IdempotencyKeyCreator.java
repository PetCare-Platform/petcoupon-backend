package com.mycom.petcoupon.idempotency.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * IdempotencyKey를 IDENTITY 전략으로 새로 INSERT하는 부분만 담당하는 협력 빈.
 *
 * 별도 클래스로 분리한 이유: 이 INSERT는 유니크 제약(user_id, idempotency_key) 위반으로 실패할 수 있는데,
 * IDENTITY 전략은 save() 호출 시점에 즉시 INSERT가 나가서 그 실패가 Hibernate 영속성 컨텍스트를 오염시킨다.
 * 같은 트랜잭션 안에서 실패를 catch하고 이어서 SELECT를 하면 "세션이 이미 rollback-only" 오류가 난다.
 * 그래서 이 메서드만 REQUIRES_NEW로 별도 트랜잭션에 태워, 실패해도 그 트랜잭션만 롤백되고
 * 호출자(IdempotencyKeyServiceImpl.begin())의 트랜잭션·세션은 멀쩡하게 남아 재조회를 이어갈 수 있게 한다.
 */
@Component
@RequiredArgsConstructor
class IdempotencyKeyCreator {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final AppUserRepository appUserRepository;
    private final CouponRepository couponRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyKey create(Long userId, Long couponId, String idempotencyKey, String requestHash, LocalDateTime expiresAt) {
        // getReferenceById는 실제 SELECT 없이 프록시만 만든다 — 여기선 FK로 걸어두는 용도라 그걸로 충분하다.
        // (coupon·userId 존재 여부는 컨트롤러가 이미 확인하고 들어왔다는 전제)
        AppUser userRef = appUserRepository.getReferenceById(userId);
        Coupon couponRef = couponRepository.getReferenceById(couponId);

        return idempotencyKeyRepository.save(
                IdempotencyKey.builder()
                        .user(userRef)
                        .coupon(couponRef)
                        .idempotencyKey(idempotencyKey)
                        .requestHash(requestHash)
                        .expiresAt(expiresAt)
                        .build()
        );
    }
}
