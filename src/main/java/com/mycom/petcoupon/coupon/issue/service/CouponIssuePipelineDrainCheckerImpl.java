package com.mycom.petcoupon.coupon.issue.service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
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
 * 잔여 메시지의 네 종류를 구분해서 본다 — 남는 방식에 따라 결과가 다르기 때문이다.
 *
 * <table border="1">
 *   <caption>잔여 메시지의 네 종류</caption>
 *   <tr><th>상태</th><th>다음에</th><th>결과</th><th>집계</th></tr>
 *   <tr><td>Stream 미배달</td><td>새 Consumer 가 읽어감</td><td>유령 발급</td><td><b>{@code streamUndelivered}에 포함</b></td></tr>
 *   <tr><td>Outbox PENDING·FAILED·SENT</td><td>poller 또는 이미 배달된 Consumer 가 마저 처리</td><td>유령 발급</td><td><b>{@code outboxUnconsumed}에 포함</b></td></tr>
 *   <tr><td>Stream pending, idle 짧음(방금 배달)</td><td>이미 붙은 Consumer 가 곧 ACK</td><td>유령 발급</td><td><b>{@code streamActivePending}에 포함</b></td></tr>
 *   <tr><td>Stream pending, idle 김(오래 방치)</td><td>아무도 안 가져감(죽은 Consumer)</td><td>신청 유실</td><td>경고 로그만, 집계에는 안 넣음</td></tr>
 * </table>
 *
 * <p>SENT를 Outbox 집계에 넣는 이유 — SENT는 Kafka 발행까지만 끝난 상태고 DB 저장(CONSUMED)은
 * 아직이다. 이 상태를 안 세면 이미 Kafka로 나가 Consumer가 곧 처리할 메시지가 있는데도 드레인
 * 완료로 판단해버린다 — 그 타이밍에 초기화나 정합성 검증을 허용하면 Consumer가 뒤늦게 저장한
 * 데이터가 이번 회차/판정을 어긋나게 만든다.
 *
 * <p>pending을 idle 시간으로 가르는 이유 — 방금 배달된 pending(idle 짧음)은 이미 배달받은
 * Consumer가 몇 초 안에 ACK할 것이므로 미배달·미소비와 똑같이 유령 발급을 만든다. 반면 오래
 * 방치된 pending은 Consumer 이름이 기동할 때마다 새로 생겨 죽은 Consumer가 잡고 있던 채로
 * 아무도 회수하지 않는 경우다 — 재처리되지 않아 유령 발급을 만들지 않으므로, 이것까지 막으면
 * 호출자가 영구히 막히게 된다. 두 상태를 가르는 기준은
 * {@code coupon.issue.stream.pending-idle-threshold-ms} — 정상 처리(DB 저장 1건)는 밀리초
 * 단위로 끝나므로 기본값(30초)도 넉넉한 여유고, 죽은 Consumer 판정과는 자릿수가 다르다.
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
        long outboxUnconsumed = countUnconsumedMessages(couponId);

        try {
            StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
            String streamKey = streamProperties.getKey();
            long streamUndelivered = 0L;
            long streamActivePending = 0L;

            // 스트림이 아직 안 만들어졌으면 남은 메시지도 없다.
            if (Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
                // XINFO GROUPS 는 미배달 건수를 직접 주지 않는다. 마지막으로 쌓인 ID 와
                // 그룹이 마지막으로 배달한 ID 가 다르면 아직 아무도 안 가져간 신청이 있다는 뜻이다.
                String lastGeneratedId = streamOps.info(streamKey).lastGeneratedId();

                List<StreamInfo.XInfoGroup> groups = streamOps.groups(streamKey).stream()
                        .filter(group -> group.groupName().equals(streamProperties.getGroup()))
                        .toList();

                for (StreamInfo.XInfoGroup group : groups) {
                    if (!Objects.equals(group.lastDeliveredId(), lastGeneratedId)) {
                        streamUndelivered++;
                    }
                    streamActivePending += countActivePending(streamOps, streamKey, group);
                }
            }

            return new PipelineDrainStatus(outboxUnconsumed, streamUndelivered, streamActivePending, false);
        } catch (DataAccessException e) {
            log.error("[PipelineDrain] 파이프라인 잔여 검사 실패. couponId={}", couponId, e);
            return new PipelineDrainStatus(outboxUnconsumed, 0L, 0L, true);
        }
    }

    /**
     * pending 중 idle 시간이 임계값보다 짧은(=최근 배달된) 것만 센다. 오래 방치된 pending은
     * 죽은 Consumer가 잡고 있을 뿐 재처리되지 않으므로 집계에서 빼고 경고 로그만 남긴다
     * (클래스 주석 참고).
     */
    private long countActivePending(StreamOperations<String, Object, Object> streamOps, String streamKey,
            StreamInfo.XInfoGroup group) {
        Long totalPending = group.pendingCount();
        if (totalPending == null || totalPending == 0) {
            return 0L;
        }

        // XPENDING의 IDLE 필터는 "이 시간 이상 방치된 것"만 뽑아준다 — 그래서 전체 중 임계값을
        // 넘긴 것(stale)을 먼저 세고, 나머지(전체 - stale)를 "최근 배달돼 아직 처리 중"으로 본다.
        PendingMessages stale = streamOps.pending(
                streamKey, group.groupName(), Range.unbounded(), totalPending,
                Duration.ofMillis(streamProperties.getPendingIdleThresholdMs())
        );
        long staleCount = stale == null ? 0L : stale.size();
        long activeCount = Math.max(0L, totalPending - staleCount);

        if (staleCount > 0) {
            log.warn(
                    "[PipelineDrain] 회수되지 않은 Stream pending 이 {}건 있다. 그만큼의 신청이 판정 없이 사라졌다는 뜻이다. "
                            + "정리 방법은 load-test/README.md 참고.",
                    staleCount
            );
        }

        return activeCount;
    }

    /** Outbox 에 아직 Kafka 로 안 나갔거나(PENDING·FAILED), 나갔지만 DB 저장은 안 끝난(SENT) 건. */
    private long countUnconsumedMessages(Long couponId) {
        return entityManager.createQuery("""
                        SELECT COUNT(m) FROM IssueMessage m
                         WHERE m.coupon.couponId = :couponId
                           AND m.status IN (
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.PENDING,
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.SENT,
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.FAILED)
                        """, Long.class)
                .setParameter("couponId", couponId)
                .getSingleResult();
    }
}
