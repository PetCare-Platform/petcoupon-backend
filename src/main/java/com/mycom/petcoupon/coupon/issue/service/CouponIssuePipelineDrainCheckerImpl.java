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
 * 잔여 메시지의 세 종류를 구분해서 본다 — 남는 방식에 따라 결과가 다르기 때문이다.
 *
 * <table border="1">
 *   <caption>잔여 메시지의 세 종류</caption>
 *   <tr><th>상태</th><th>다음에</th><th>결과</th><th>집계</th></tr>
 *   <tr><td>Stream 미배달(Consumer Group 존재)</td><td>Consumer 가 읽어감</td><td>유령 발급</td><td><b>{@code streamUndelivered}에 포함</b></td></tr>
 *   <tr><td>Stream 존재, Consumer Group 없음</td><td>Group 복구 시 0-0부터 재전달</td><td>유령 발급</td><td><b>{@code streamUndelivered}에 포함</b></td></tr>
 *   <tr><td>Outbox PENDING·FAILED·SENT</td><td>poller 또는 이미 배달된 Consumer 가 마저 처리</td><td>유령 발급</td><td><b>{@code outboxUnconsumed}에 포함</b></td></tr>
 *   <tr><td>Stream pending(전부)</td><td>회수 스케줄러(CouponIssuePendingRecoveryScheduler)가 XCLAIM으로 재처리</td><td>유령 발급</td><td><b>{@code streamActivePending}에 포함</b></td></tr>
 * </table>
 *
 * <p>SENT를 Outbox 집계에 넣는 이유 — SENT는 Kafka 발행까지만 끝난 상태고 DB 저장(CONSUMED)은
 * 아직이다. 이 상태를 안 세면 이미 Kafka로 나가 Consumer가 곧 처리할 메시지가 있는데도 드레인
 * 완료로 판단해버린다 — 그 타이밍에 초기화나 정합성 검증을 허용하면 Consumer가 뒤늦게 저장한
 * 데이터가 이번 회차/판정을 어긋나게 만든다.
 *
 * <p>pending을 idle 시간으로 가르지 않고 무조건 막는 이유 — 이전에는 idle이 짧으면(방금 배달)
 * 막고 길면(오래 방치) 안 막았는데, idle 시간은 "마지막 배달 이후 경과 시간"일 뿐 Consumer의
 * 생사를 알려주지 않는다. DB 지연·GC로 정상 처리 중인 메시지도 오래 방치된 것처럼 보일 수 있어
 * 그 구분 자체가 안전하지 않았다. 이제는 pending이면 무조건 막는다 — 대신
 * {@code CouponIssuePendingRecoveryScheduler}가 주기적으로 XCLAIM해 실제로 회수·재처리하므로,
 * 죽은 Consumer가 잡고 있던 pending도 영구히 막히지 않고 스케줄러가 정리하는 대로 풀린다.
 *
 * <p>Consumer Group이 없어도(예: Redis 재기동) Stream에 메시지가 남아있으면 미배달로 본다 —
 * Group이 복구되면서 0-0부터 기존 메시지가 다시 전달될 수 있기 때문이다.
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

                StreamInfo.XInfoGroup issueGroup = streamOps.groups(streamKey).stream()
                        .filter(group -> group.groupName().equals(streamProperties.getGroup()))
                        .findFirst()
                        .orElse(null);

                if (issueGroup == null) {
                    // Stream은 있는데 Consumer Group이 없다 — Group이 복구되면 0-0부터
                    // 기존 메시지가 다시 전달될 수 있으므로 미배달로 본다.
                    Long streamSize = streamOps.size(streamKey);
                    if (streamSize != null && streamSize > 0) {
                        streamUndelivered = 1L;
                    }
                } else {
                    if (!Objects.equals(issueGroup.lastDeliveredId(), lastGeneratedId)) {
                        streamUndelivered = 1L;
                    }

                    // pending은 idle 시간과 무관하게 전부 막는다 — 회수 스케줄러가 알아서
                    // XCLAIM으로 재처리하므로 영구히 막히지 않는다(클래스 주석 참고).
                    Long pendingCount = issueGroup.pendingCount();
                    if (pendingCount != null) {
                        streamActivePending = pendingCount;
                    }
                }
            }

            return new PipelineDrainStatus(outboxUnconsumed, streamUndelivered, streamActivePending, false);
        } catch (DataAccessException e) {
            log.error("[PipelineDrain] 파이프라인 잔여 검사 실패. couponId={}", couponId, e);
            return new PipelineDrainStatus(outboxUnconsumed, 0L, 0L, true);
        }
    }

    /**
     * Outbox 에 아직 Kafka 로 안 나갔거나(PENDING·FAILED), 나갔지만 DB 저장은 안 끝난(SENT) 건.
     * [PR 리뷰 반영] REPROCESSING도 포함한다 — 관리자가 DLQ 재처리를 선점했지만 아직 재발행이
     * CONSUMED로 확정되지 않은 진행 중 상태라, 이걸 빼면 재처리 도중에도 파이프라인이 소진된
     * 것으로 오판해 정합성 검증·초기화가 시작될 수 있다.
     */
    private long countUnconsumedMessages(Long couponId) {
        return entityManager.createQuery("""
                        SELECT COUNT(m) FROM IssueMessage m
                         WHERE m.coupon.couponId = :couponId
                           AND m.status IN (
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.PENDING,
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.SENT,
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.FAILED,
                               com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus.REPROCESSING)
                        """, Long.class)
                .setParameter("couponId", couponId)
                .getSingleResult();
    }
}
