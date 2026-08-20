package com.mycom.petcoupon.idempotency.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * API 레벨 멱등성 처리 — "같은 Idempotency-Key로 온 요청은 한 번만 본처리한다"를 구현한다.
 *
 * 트랜잭션 경계가 중요하다: MySQL(idempotency_key)과 Redis는 별개 시스템이라 하나의 트랜잭션으로
 * 묶을 수 없다. 그래서 begin()과 succeed()/fail()/failWithoutBody()는 항상 별도 트랜잭션으로 분리돼 있다.
 *  - begin()이 커밋돼야, 그 이후 Redis 호출 중에 서버가 죽어도 IN_PROGRESS 행이 DB에 남아있어서
 *    expires_at으로 "죽은 시도"인지 판단할 수 있다 (안 그러면 아예 기록조차 안 남아 재시도가 무한정 새 시도로 처리됨).
 *  - succeed()/fail()은 본처리(Redis 호출 포함)가 끝난 뒤 그 결과를 별도 트랜잭션으로 반영한다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyKeyService {

    // IN_PROGRESS 상태가 이 시간을 넘기면 죽은 시도로 간주하고 재처리를 허용한다.
    private static final Duration TTL = Duration.ofSeconds(30);

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final AppUserRepository appUserRepository;
    private final CouponRepository couponRepository;

    /**
     * 컨트롤러가 본처리 시작 전에 제일 먼저 호출하는 진입점.
     * (user, idempotency_key)로 기존 시도를 찾아서, 상황에 맞는 IdempotencyDecision을 돌려준다.
     */
    @Transactional
    public IdempotencyDecision begin(Long userId, Long couponId, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now();
        String requestHash = hash(couponId, userId);

        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(userId, idempotencyKey);

        // 케이스 1: 처음 보는 키 — IN_PROGRESS로 새로 INSERT하고 바로 본처리 진행(PROCEED)
        if (existing.isEmpty()) {
            // getReferenceById는 실제 SELECT 없이 프록시만 만든다 — 여기선 FK로 걸어두는 용도라 그걸로 충분하다.
            // (coupon 존재 여부는 컨트롤러가 이미 확인하고 들어왔다는 전제)
            AppUser userRef = appUserRepository.getReferenceById(userId);
            Coupon couponRef = couponRepository.getReferenceById(couponId);

            IdempotencyKey created = idempotencyKeyRepository.save(
                    IdempotencyKey.builder()
                            .user(userRef)
                            .coupon(couponRef)
                            .idempotencyKey(idempotencyKey)
                            .requestHash(requestHash)
                            .expiresAt(now.plus(TTL))
                            .build()
            );
            return IdempotencyDecision.proceed(created.getIdempotencyId());
        }

        IdempotencyKey record = existing.get();

        // 케이스 2: 같은 키인데 요청 내용(coupon/user 조합)이 다름 — 클라이언트가 키를 잘못 재사용한 것
        if (!record.matchesRequest(requestHash)) {
            return IdempotencyDecision.keyReused();
        }

        // 케이스 3~5: 같은 키 + 같은 요청 내용 — 기존 시도의 상태에 따라 분기
        return switch (record.getStatus()) {
            // 3. 이미 성공까지 끝남 — 재실행하지 않고 그때 응답을 그대로 재현
            case SUCCEEDED -> IdempotencyDecision.replay(record.getResponseStatus(), record.getResponseBody());

            // 4. 이미 실패로 끝남 — 두 가지로 나뉜다
            //    - 응답이 저장된 FAILED: 정상적으로 끝까지 처리됐다가 (재고소진 등으로) 실패한 것 → 그대로 재현
            //    - 응답이 없는 FAILED: Redis 호출 자체가 예외로 끊긴 것(failWithoutBody) → 재시도를 허용
            case FAILED -> record.getResponseBody() != null
                    ? IdempotencyDecision.replay(record.getResponseStatus(), record.getResponseBody())
                    : reclaim(record, now);

            // 5. 아직 처리 중 — 만료 여부로 "정말 처리 중"인지 "서버가 죽어서 멈춘 죽은 시도"인지 구분
            case IN_PROGRESS -> record.isExpired(now)
                    ? reclaim(record, now)
                    : IdempotencyDecision.conflict();
        };
    }

    // 죽은 시도(만료된 IN_PROGRESS, 또는 응답 없는 FAILED)를 이어받아 본처리를 다시 시작하게 한다.
    private IdempotencyDecision reclaim(IdempotencyKey record, LocalDateTime now) {
        record.reclaim(now.plus(TTL));
        return IdempotencyDecision.proceed(record.getIdempotencyId());
    }

    // 본처리가 성공으로 끝났을 때 컨트롤러가 호출 — 실제 성공 응답을 그대로 저장해서 다음 재요청 때 재현한다.
    @Transactional
    public void succeed(Long recordId, int responseStatus, String responseBody) {
        idempotencyKeyRepository.findById(recordId)
                .ifPresent(record -> record.complete(IdempotencyStatus.SUCCEEDED, responseStatus, responseBody));
    }

    // 본처리가 "정상적으로 끝까지 실행됐지만" 실패로 끝났을 때(재고소진, 중복신청 등) 호출.
    // 응답을 저장해두므로 같은 키로 재시도하면 재실행 없이 이 실패가 그대로 재현된다.
    @Transactional
    public void fail(Long recordId, int responseStatus, String responseBody) {
        idempotencyKeyRepository.findById(recordId)
                .ifPresent(record -> record.complete(IdempotencyStatus.FAILED, responseStatus, responseBody));
    }

    // Redis 호출 자체가 예외를 던져서 본처리가 끊긴 경우 호출.
    // 이 시점엔 만들어줄 응답이 없으므로 body 없이 FAILED 처리한다 — 목적은 두 가지:
    //  1) 영구 IN_PROGRESS 방지 (서버가 죽지 않았어도 이 레코드가 계속 "처리 중"으로 남는 것을 막음)
    //  2) begin()에서 이 레코드를 다시 만나면 응답이 없는 FAILED로 판단해서 재시도를 허용하게 함
    @Transactional
    public void failWithoutBody(Long recordId) {
        idempotencyKeyRepository.findById(recordId)
                .ifPresent(record -> record.complete(IdempotencyStatus.FAILED, null, null));
    }

    // 요청을 식별하는 해시 — 지금은 (couponId, userId) 조합만 넣는다.
    // 이 값이 idempotency_key 저장 당시와 다르면 "같은 키를 다른 요청에 재사용"한 것으로 본다.
    private String hash(Long couponId, Long userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((couponId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
