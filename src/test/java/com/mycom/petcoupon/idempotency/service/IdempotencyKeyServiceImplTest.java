package com.mycom.petcoupon.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.user.entity.AppUser;

@ExtendWith(MockitoExtension.class)
class IdempotencyKeyServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long COUPON_ID = 100L;
    private static final String KEY = "idem-key-1";

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private IdempotencyKeyCreator idempotencyKeyCreator;

    @InjectMocks
    private IdempotencyKeyServiceImpl idempotencyKeyService;

    @Test
    void 신규_키면_INSERT를_바로_시도하고_PROCEED를_반환한다() {
        IdempotencyKey created = existing(IdempotencyStatus.IN_PROGRESS, LocalDateTime.now().plusSeconds(30), null, null);
        setId(created, 10L);
        when(idempotencyKeyCreator.create(eq(USER_ID), eq(COUPON_ID), eq(KEY), any(), any())).thenReturn(created);

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.PROCEED);
        assertThat(decision.recordId()).isEqualTo(10L);
    }

    @Test
    void 동시_요청으로_유니크_제약_위반이_나면_재조회해서_기존_레코드_기준으로_판단한다() {
        when(idempotencyKeyCreator.create(eq(USER_ID), eq(COUPON_ID), eq(KEY), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uk_idem_user_key violated"));

        IdempotencyKey wonByOtherRequest = existing(IdempotencyStatus.IN_PROGRESS, LocalDateTime.now().plusSeconds(30), null, null);
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(wonByOtherRequest));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        // 내가 INSERT 경쟁에서 졌으니, 먼저 이긴 요청의 상태(IN_PROGRESS·안 만료)를 그대로 따른다
        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.CONFLICT);
    }

    @Test
    void 유니크_제약_위반인데_재조회해도_없으면_원래_예외를_그대로_던진다() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("uk_idem_user_key violated");
        when(idempotencyKeyCreator.create(eq(USER_ID), eq(COUPON_ID), eq(KEY), any(), any())).thenThrow(original);
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY))
                .isSameAs(original);
    }

    @Test
    void 처리중이고_만료_안됐으면_CONFLICT를_반환한다() {
        givenRaceLostTo(existing(IdempotencyStatus.IN_PROGRESS, LocalDateTime.now().plusSeconds(30), null, null));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.CONFLICT);
    }

    @Test
    void 처리중이고_만료됐으면_재사용하고_PROCEED를_반환한다() {
        IdempotencyKey record = existing(IdempotencyStatus.IN_PROGRESS, LocalDateTime.now().minusSeconds(1), null, null);
        setId(record, 20L);
        givenRaceLostTo(record);

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.PROCEED);
        assertThat(decision.recordId()).isEqualTo(20L);
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.isExpired(LocalDateTime.now())).isFalse();
    }

    @Test
    void 완료된_SUCCEEDED_키면_저장된_응답을_그대로_REPLAY한다() {
        givenRaceLostTo(existing(IdempotencyStatus.SUCCEEDED, LocalDateTime.now().plusSeconds(30), 200, "{\"isSuccess\":true}"));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.REPLAY);
        assertThat(decision.replayStatus()).isEqualTo(200);
        assertThat(decision.replayBody()).isEqualTo("{\"isSuccess\":true}");
    }

    @Test
    void 응답이_저장된_FAILED_키면_그대로_REPLAY한다() {
        givenRaceLostTo(existing(IdempotencyStatus.FAILED, LocalDateTime.now().plusSeconds(30), 409, "{\"isSuccess\":false}"));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.REPLAY);
        assertThat(decision.replayStatus()).isEqualTo(409);
    }

    @Test
    void 응답없는_FAILED_키면_Redis_예외로_끊긴_시도로_보고_재처리를_허용한다() {
        IdempotencyKey record = existing(IdempotencyStatus.FAILED, LocalDateTime.now().plusSeconds(30), null, null);
        setId(record, 30L);
        givenRaceLostTo(record);

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.PROCEED);
        assertThat(decision.recordId()).isEqualTo(30L);
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void 같은_키인데_요청내용이_다르면_KEY_REUSED를_반환한다() {
        // couponId가 200L인 요청으로 만든 키 — 아래에서 COUPON_ID(100L)로 재요청하면 해시가 달라짐
        givenRaceLostTo(existingForCoupon(200L, IdempotencyStatus.IN_PROGRESS, LocalDateTime.now().plusSeconds(30)));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.KEY_REUSED);
    }

    @Test
    void 보관기간이_지난_레코드를_삭제하고_삭제된_개수를_반환한다() {
        when(idempotencyKeyRepository.deleteByCreatedAtBefore(any())).thenReturn(5);

        int deletedCount = idempotencyKeyService.cleanupExpiredRecords();

        assertThat(deletedCount).isEqualTo(5);

        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(idempotencyKeyRepository).deleteByCreatedAtBefore(thresholdCaptor.capture());

        LocalDateTime expectedThreshold = LocalDateTime.now().minusDays(7);
        assertThat(thresholdCaptor.getValue()).isCloseTo(expectedThreshold, within(5, ChronoUnit.SECONDS));
    }

    // "이미 존재하는 레코드를 만난 상태"를 시뮬레이션 — 실제로는 항상 INSERT를 먼저 시도하므로
    // (신규 키가 아닌 이상) 유니크 제약 위반 → 재조회 경로를 통해서만 이 상태에 도달한다.
    private void givenRaceLostTo(IdempotencyKey record) {
        when(idempotencyKeyCreator.create(eq(USER_ID), eq(COUPON_ID), eq(KEY), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uk_idem_user_key violated"));
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(record));
    }

    private static IdempotencyKey existing(IdempotencyStatus status, LocalDateTime expiresAt, Integer responseStatus, String responseBody) {
        return existingForCoupon(COUPON_ID, status, expiresAt, responseStatus, responseBody);
    }

    private static IdempotencyKey existingForCoupon(Long couponId, IdempotencyStatus status, LocalDateTime expiresAt) {
        return existingForCoupon(couponId, status, expiresAt, null, null);
    }

    private static IdempotencyKey existingForCoupon(
            Long couponId, IdempotencyStatus status, LocalDateTime expiresAt, Integer responseStatus, String responseBody) {
        IdempotencyKey record = IdempotencyKey.builder()
                .user(mock(AppUser.class))
                .coupon(mock(Coupon.class))
                .idempotencyKey(KEY)
                .requestHash(hash(couponId, USER_ID))
                .expiresAt(expiresAt)
                .build();
        if (status != IdempotencyStatus.IN_PROGRESS) {
            record.complete(status, responseStatus, responseBody);
        }
        return record;
    }

    private static String hash(Long couponId, Long userId) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((couponId + ":" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setId(IdempotencyKey entity, Long id) {
        try {
            var field = IdempotencyKey.class.getDeclaredField("idempotencyId");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
