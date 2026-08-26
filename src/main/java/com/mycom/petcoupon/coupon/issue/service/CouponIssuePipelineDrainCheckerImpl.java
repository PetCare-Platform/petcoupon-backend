package com.mycom.petcoupon.coupon.issue.service;

import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 잔여 메시지의 두 종류를 구분해서 본다 — 남는 방식에 따라 결과가 정반대이기 때문이다.
 *
 * <table border="1">
 *   <caption>잔여 메시지의 두 종류</caption>
 *   <tr><th>상태</th><th>다음에</th><th>결과</th><th>집계</th></tr>
 *   <tr><td>Stream 미배달</td><td>새 Consumer 가 읽어감</td><td>유령 발급</td><td><b>{@code streamUndelivered}에 포함</b></td></tr>
 *   <tr><td>Outbox 미발행</td><td>poller 가 Kafka 로 보냄</td><td>유령 발급</td><td><b>{@code outboxUnpublished}에 포함</b></td></tr>
 *   <tr><td>Stream pending</td><td>아무도 안 가져감</td><td>신청 유실</td><td>경고 로그만, 집계에는 안 넣음</td></tr>
 * </table>
 *
 * <p>pending 을 집계에 안 넣는 이유가 있다. Consumer 이름이 기동할 때마다 새로 생겨서
 * 죽은 Consumer 가 잡고 있던 pending 은 아무도 회수하지 않는다. 그런 잔여물은 계속 쌓이기만 하고
 * 재처리되지 않으므로 유령 발급을 만들지 않는데, 이걸로 막으면 호출자가 영구히 막히게 된다.
 *
 * <p>Kafka 로 이미 나간 메시지는 여기서 볼 수 없다. 다만 Outbox 가 비었으면 새로 나갈 것이 없고,
 * 이미 나간 것은 Consumer 가 곧 소비한다. 완전한 보증은 아니다.
 *
 * <p>Stream 은 쿠폰별로 나뉘어 있지 않아 검사도 전역이다. 다른 쿠폰의 잔여물이라도
 * 환경이 깨끗하지 않다는 신호이므로 그대로 반영한다.
 *
 * <p><b>검사 자체가 실패하면 {@code checkFailed=true}로 돌려준다.</b> Redis 에 닿지 못했다는 건
 * "남은 게 없다"가 아니라 "남았는지 모른다"는 뜻이라, 호출자가 안전한 쪽으로 판단할 수 있게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssuePipelineDrainCheckerImpl implements CouponIssuePipelineDrainChecker {

    @PersistenceContext
    private EntityManager entityManager;

    private final StringRedisTemplate redisTemplate;
    private final CouponIssueStreamProperties streamProperties;

    @Override
    public PipelineDrainStatus check(Long couponId) {
        long outboxUnpublished = countUnpublishedMessages(couponId);

        try {
            StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
            String streamKey = streamProperties.getKey();
            long streamUndelivered = 0L;

            // 스트림이 아직 안 만들어졌으면 남은 메시지도 없다.
            if (Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
                // XINFO GROUPS 는 미배달 건수를 직접 주지 않는다. 마지막으로 쌓인 ID 와
                // 그룹이 마지막으로 배달한 ID 가 다르면 아직 아무도 안 가져간 신청이 있다는 뜻이다.
                String lastGeneratedId = streamOps.info(streamKey).lastGeneratedId();

                streamUndelivered = streamOps.groups(streamKey).stream()
                        .filter(group -> group.groupName().equals(streamProperties.getGroup()))
                        .peek(this::warnIfPendingRemains)
                        .filter(group -> !Objects.equals(group.lastDeliveredId(), lastGeneratedId))
                        .count();
            }

            return new PipelineDrainStatus(outboxUnpublished, streamUndelivered, false);
        } catch (DataAccessException e) {
            log.error("[PipelineDrain] 파이프라인 잔여 검사 실패. couponId={}", couponId, e);
            return new PipelineDrainStatus(outboxUnpublished, 0L, true);
        }
    }

    /**
     * 회수되지 않는 pending 은 집계에 안 넣고 알리기만 한다. 재처리되지 않아 유령 발급을 만들지는
     * 않지만, 그만큼의 신청이 판정도 못 받고 사라졌다는 뜻이라 결과를 읽을 때 감안해야 한다.
     */
    private void warnIfPendingRemains(StreamInfo.XInfoGroup group) {
        Long pending = group.pendingCount();

        if (pending != null && pending > 0) {
            log.warn(
                    "[PipelineDrain] 회수되지 않은 Stream pending 이 {}건 있다. 그만큼의 신청이 판정 없이 사라졌다는 뜻이다. "
                            + "정리 방법은 load-test/README.md 참고.",
                    pending
            );
        }
    }

    /** Outbox 에 아직 Kafka 로 안 나간 건. poller 가 다음 주기에 집어 간다. */
    private long countUnpublishedMessages(Long couponId) {
        return entityManager.createQuery("""
                        SELECT COUNT(m) FROM IssueMessage m
                         WHERE m.coupon.couponId = :couponId
                           AND m.status IN (
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.PENDING,
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.FAILED)
                        """, Long.class)
                .setParameter("couponId", couponId)
                .getSingleResult();
    }
}
