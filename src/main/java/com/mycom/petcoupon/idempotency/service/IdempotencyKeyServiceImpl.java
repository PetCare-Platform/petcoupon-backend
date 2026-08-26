package com.mycom.petcoupon.idempotency.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;

import lombok.RequiredArgsConstructor;

/**
 * 트랜잭션 경계가 중요하다: MySQL(idempotency_key)과 Redis는 별개 시스템이라 하나의 트랜잭션으로
 * 묶을 수 없다. 그래서 begin()과 succeed()/fail()/failWithoutBody()는 항상 별도 트랜잭션으로 분리돼 있다.
 *  - begin()이 커밋돼야, 그 이후 Redis 호출 중에 서버가 죽어도 IN_PROGRESS 행이 DB에 남아있어서
 *    expires_at으로 "죽은 시도"인지 판단할 수 있다 (안 그러면 아예 기록조차 안 남아 재시도가 무한정 새 시도로 처리됨).
 *  - succeed()/fail()은 본처리(Redis 호출 포함)가 끝난 뒤 그 결과를 별도 트랜잭션으로 반영한다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyKeyServiceImpl implements IdempotencyKeyService {

    // IN_PROGRESS 상태가 이 시간을 넘기면 죽은 시도로 간주하고 재처리를 허용한다.
    // 부하 테스트 목표(전건 확정 5분/300초 이내)의 2배 여유를 두고 600초로 잡았다 — 실측(p95/p99/p99.9/최대
    // 처리 시간) 나오면 조정 예정(#84). 하드코딩 대신 프로퍼티로 빼서 그때 코드 변경 없이 튜닝 가능하게 함.
    @Value("${coupon.issue.idempotency.ttl-seconds:600}")
    private long ttlSeconds;

    // 상태와 무관하게, 생성된 지 이만큼 지난 행은 재현(REPLAY)될 일이 없다고 보고 정리 대상으로 삼는다.
    // TTL(30초)과 달리 SUCCEEDED/FAILED 행은 완료 후에도 재요청 재현을 위해 한동안 남아있어야 하므로
    // expires_at이 아니라 created_at 기준으로 훨씬 긴 보관기간을 둔다.
    private static final Duration RETENTION = Duration.ofDays(7);

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final IdempotencyKeyCreator idempotencyKeyCreator;

    @Override
    @Transactional
    public IdempotencyDecision begin(Long userId, Long couponId, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now();
        String requestHash = hash(couponId, userId);

        // 먼저 INSERT를 시도한다(SELECT 먼저 하지 않음) — 동시 요청이 둘 다 "없음"을 보고
        // 둘 다 INSERT하려는 레이스를 막으려면, 유니크 제약이 있는 DB 쪽에서 승부를 내야 한다.
        // 실제 INSERT는 IdempotencyKeyCreator.create()가 REQUIRES_NEW로 별도 트랜잭션에서 수행한다
        // (같은 트랜잭션에서 실패하면 Hibernate 세션이 오염돼 바로 뒤의 재조회도 실패하기 때문).
        try {
            IdempotencyKey created = idempotencyKeyCreator.create(userId, couponId, idempotencyKey, requestHash, now.plus(ttl()));
            return IdempotencyDecision.proceed(created.getIdempotencyId());
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 INSERT를 끝냄 — 그 레코드를 다시 조회해서 기존 판단 로직을 그대로 태운다.
            IdempotencyKey record = idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> e);
            return decideForExisting(record, requestHash, now);
        }
    }

    // 이미 존재하는 레코드를 만났을 때(처음부터 SELECT로 찾은 경우 / INSERT 충돌 후 재조회한 경우 공용)의 판단 로직.
    private IdempotencyDecision decideForExisting(IdempotencyKey record, String requestHash, LocalDateTime now) {
        // 같은 키인데 요청 내용(coupon/user 조합)이 다름 — 클라이언트가 키를 잘못 재사용한 것
        if (!record.matchesRequest(requestHash)) {
            return IdempotencyDecision.keyReused();
        }

        return switch (record.getStatus()) {
            // 이미 성공까지 끝남 — 재실행하지 않고 그때 응답을 그대로 재현
            case SUCCEEDED -> IdempotencyDecision.replay(record.getResponseStatus(), record.getResponseBody());

            // 이미 실패로 끝남 — 두 가지로 나뉜다
            //    - 응답이 저장된 FAILED: 정상적으로 끝까지 처리됐다가 (재고소진 등으로) 실패한 것 → 그대로 재현
            //    - 응답이 없는 FAILED: Redis 호출 자체가 예외로 끊긴 것(failWithoutBody) → 재시도를 허용
            case FAILED -> record.getResponseBody() != null
                    ? IdempotencyDecision.replay(record.getResponseStatus(), record.getResponseBody())
                    : reclaim(record, now);

            // 아직 처리 중 — 만료 여부로 "정말 처리 중"인지 "서버가 죽어서 멈춘 죽은 시도"인지 구분
            case IN_PROGRESS -> record.isExpired(now)
                    ? reclaim(record, now)
                    : IdempotencyDecision.conflict();
        };
    }

    // 죽은 시도(만료된 IN_PROGRESS, 또는 응답 없는 FAILED)를 이어받아 본처리를 다시 시작하게 한다.
    private IdempotencyDecision reclaim(IdempotencyKey record, LocalDateTime now) {
        record.reclaim(now.plus(ttl()));
        return IdempotencyDecision.proceed(record.getIdempotencyId());
    }

    private Duration ttl() {
        return Duration.ofSeconds(ttlSeconds);
    }

    @Override
    @Transactional
    public void succeed(Long recordId, int responseStatus, String responseBody) {
        idempotencyKeyRepository.findById(recordId)
                .ifPresent(record -> {
                    // 비동기 파이프라인(Kafka Consumer/Stream Consumer)이 먼저 최종 성공(200 OK) 또는
                    // 실패(FAILED)로 확정한 경우, HTTP 스레드의 뒤늦은 202 ACCEPTED(WAITING) 접수 응답이
                    // 최종 결과를 덮어쓰지 않도록 방어한다.
                    if (responseStatus == HttpStatus.ACCEPTED.value()
                            && (record.getStatus() == IdempotencyStatus.FAILED
                            || (record.getResponseStatus() != null && record.getResponseStatus() == HttpStatus.OK.value()))) {
                        return;
                    }
                    record.complete(IdempotencyStatus.SUCCEEDED, responseStatus, responseBody);
                });
    }

    @Override
    @Transactional
    public void fail(Long recordId, int responseStatus, String responseBody) {
        idempotencyKeyRepository.findById(recordId)
                .ifPresent(record -> record.complete(IdempotencyStatus.FAILED, responseStatus, responseBody));
    }

    @Override
    @Transactional
    public void failWithoutBody(Long recordId) {
        idempotencyKeyRepository.findById(recordId)
                .ifPresent(record -> record.complete(IdempotencyStatus.FAILED, null, null));
    }

    @Override
    @Transactional
    public int cleanupExpiredRecords() {
        return idempotencyKeyRepository.deleteByCreatedAtBefore(LocalDateTime.now().minus(RETENTION));
    }

    @Override
    @Transactional(readOnly = true)
    public IdempotencyKeyStatusResult findStatus(Long userId, String idempotencyKey) {
        return idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(record -> switch (record.getStatus()) {
                    case IN_PROGRESS -> IdempotencyKeyStatusResult.inProgress();
                    case SUCCEEDED -> IdempotencyKeyStatusResult.done(record.getResponseStatus(), record.getResponseBody());
                    // 응답이 저장된 FAILED는 그대로 재현하고, 응답 없는 FAILED(인프라 예외로 끊긴 시도)는
                    // 아직 최종 결과가 없는 것이므로 IN_PROGRESS와 동일하게 취급한다 — decideForExisting()과 동일한 판단.
                    case FAILED -> record.getResponseBody() != null
                            ? IdempotencyKeyStatusResult.done(record.getResponseStatus(), record.getResponseBody())
                            : IdempotencyKeyStatusResult.inProgress();
                })
                .orElseGet(IdempotencyKeyStatusResult::notFound);
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
