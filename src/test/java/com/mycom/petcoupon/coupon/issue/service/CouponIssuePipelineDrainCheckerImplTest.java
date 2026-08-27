package com.mycom.petcoupon.coupon.issue.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Outbox 쪽은 실 DB(issue_message), Stream 쪽은 실 Redis Stream + Consumer Group으로 검증한다.
 * XINFO GROUPS 기반 판단(lastDeliveredId vs lastGeneratedId)과 XPENDING 기반 idle 판단은
 * 목으로는 의미 있게 재현이 안 된다.
 *
 * pending-idle-threshold-ms를 300ms로 짧게 잡는다 — "방금 배달된 pending"과 "오래 방치된
 * pending"을 가르는 테스트가 몇 초씩 기다리지 않고도 돌아가게 하기 위함이다(운영값 30초는
 * application.properties 참고).
 *
 * 실행 전 MySQL/Redis가 떠 있어야 한다: docker compose up -d
 * 실제 앱과 같은 전역 Stream 키를 쓰면 다른 테스트/실행 중인 앱과 충돌하므로, 이 테스트 전용
 * 키·그룹으로 오버라이드한다.
 */
@DataJpaTest(properties = {
        "coupon.issue.stream.key=coupon:issue:stream:drain-checker-test",
        "coupon.issue.stream.group=drain-checker-test-group",
        "coupon.issue.stream.pending-idle-threshold-ms=300"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CouponIssuePipelineDrainCheckerImpl.class)
@ImportAutoConfiguration(DataRedisAutoConfiguration.class)
@EnableConfigurationProperties(CouponIssueStreamProperties.class)
class CouponIssuePipelineDrainCheckerImplTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private CouponIssuePipelineDrainChecker checker;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CouponIssueStreamProperties streamProperties;

    private Coupon coupon;

    @BeforeEach
    void setUp() {
        AppUser admin = AppUser.builder()
                .name("드레인체크 관리자").email("drain-checker@test.com").phone("010-0000-0000")
                .role(UserRole.ROLE_ADMIN).build();
        entityManager.persist(admin);

        Event event = Event.builder()
                .createdBy(admin).name("드레인체크 이벤트").description("d")
                .openAt(LocalDateTime.now()).closeAt(LocalDateTime.now().plusDays(1)).build();
        entityManager.persist(event);

        coupon = Coupon.builder()
                .event(event).name("드레인체크 쿠폰").discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(1000).minOrderAmount(1000)
                .issueStartAt(LocalDateTime.now()).issueEndAt(LocalDateTime.now().plusDays(1))
                .validDays(7).build();
        entityManager.persist(coupon);
        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(streamProperties.getKey());
    }

    @Test
    void Outbox에_PENDING이_있으면_outboxUnconsumed로_잡힌다() {
        insertIssueMessage("PENDING");

        PipelineDrainStatus status = checker.check(coupon.getCouponId());

        assertThat(status.outboxUnconsumed()).isEqualTo(1);
        assertThat(status.checkFailed()).isFalse();
        assertThat(status.isBlocked()).isTrue();
    }

    @Test
    void Outbox에_SENT가_있으면_outboxUnconsumed로_잡힌다() {
        // SENT는 Kafka 발행까지만 끝나고 DB 저장(CONSUMED)은 아직인 상태 — 미확정으로 잡혀야 한다.
        insertIssueMessage("SENT");

        PipelineDrainStatus status = checker.check(coupon.getCouponId());

        assertThat(status.outboxUnconsumed()).isEqualTo(1);
        assertThat(status.isBlocked()).isTrue();
    }

    @Test
    void Outbox에_CONSUMED만_있으면_outboxUnconsumed는_0이다() {
        insertIssueMessage("CONSUMED");

        PipelineDrainStatus status = checker.check(coupon.getCouponId());

        assertThat(status.outboxUnconsumed()).isZero();
        assertThat(status.isBlocked()).isFalse();
    }

    @Test
    void Stream_키가_없으면_모든_집계가_0이다() {
        PipelineDrainStatus status = checker.check(coupon.getCouponId());

        assertThat(status.streamUndelivered()).isZero();
        assertThat(status.streamActivePending()).isZero();
        assertThat(status.outboxUnconsumed()).isZero();
        assertThat(status.isBlocked()).isFalse();
    }

    @Test
    void Stream에_아무도_안_가져간_메시지가_있으면_streamUndelivered로_잡힌다() {
        StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
        String key = streamProperties.getKey();

        streamOps.add(key, Map.of("couponId", coupon.getCouponId().toString()));
        // 그룹을 0-0부터 만들어서, 이미 쌓인 메시지가 있어도 아직 아무도 안 가져간 상태로 시작한다.
        streamOps.createGroup(key, ReadOffset.from("0-0"), streamProperties.getGroup());

        PipelineDrainStatus status = checker.check(coupon.getCouponId());

        assertThat(status.streamUndelivered()).isEqualTo(1);
        assertThat(status.isBlocked()).isTrue();
    }

    @Test
    void 방금_배달돼_idle이_짧은_pending은_streamActivePending으로_잡힌다() throws InterruptedException {
        StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
        String key = streamProperties.getKey();

        streamOps.add(key, Map.of("couponId", coupon.getCouponId().toString()));
        streamOps.createGroup(key, ReadOffset.from("0-0"), streamProperties.getGroup());

        // 실제로 읽어가서 lastDeliveredId를 lastGeneratedId까지 끌어올린다(ACK는 안 한다 — pending으로 남는다).
        streamOps.read(
                Consumer.from(streamProperties.getGroup(), "test-consumer"),
                StreamOffset.create(key, ReadOffset.lastConsumed())
        );

        // 임계값(300ms) 안에 바로 확인 — 아직 idle이 짧으므로 "곧 ACK될 것"으로 봐야 한다.
        PipelineDrainStatus status = checker.check(coupon.getCouponId());

        assertThat(status.streamUndelivered()).isZero();
        assertThat(status.streamActivePending()).isEqualTo(1);
        assertThat(status.isBlocked()).isTrue();
    }

    @Test
    void 오래_방치된_pending은_streamActivePending에_안_잡히고_경고만_남긴다() throws InterruptedException {
        StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
        String key = streamProperties.getKey();

        streamOps.add(key, Map.of("couponId", coupon.getCouponId().toString()));
        streamOps.createGroup(key, ReadOffset.from("0-0"), streamProperties.getGroup());

        streamOps.read(
                Consumer.from(streamProperties.getGroup(), "dead-consumer"),
                StreamOffset.create(key, ReadOffset.lastConsumed())
        );

        // 임계값(300ms)을 넘길 때까지 기다린다 — 죽은 Consumer가 방치한 상황을 흉내낸다.
        Thread.sleep(400);

        PipelineDrainStatus status = checker.check(coupon.getCouponId());

        assertThat(status.streamUndelivered()).isZero();
        assertThat(status.streamActivePending()).isZero();
        assertThat(status.isBlocked()).isFalse();
    }

    private void insertIssueMessage(String status) {
        entityManager.createNativeQuery("""
                INSERT INTO issue_message
                    (coupon_id, user_id, sequence_no, message_key, topic, payload, status, retry_count, created_at)
                VALUES (:couponId, 1, 1, 'drain-check-req', 'coupon-issue-events', '{}', :status, 0, NOW(6))
                """)
                .setParameter("couponId", coupon.getCouponId())
                .setParameter("status", status)
                .executeUpdate();
    }
}
