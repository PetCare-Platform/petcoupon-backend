package com.mycom.petcoupon.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.idempotency.entity.IdempotencyKey;
import com.mycom.petcoupon.idempotency.entity.enums.IdempotencyStatus;
import com.mycom.petcoupon.idempotency.repository.IdempotencyKeyRepository;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class IdempotencyKeyServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long COUPON_ID = 100L;
    private static final String KEY = "idem-key-1";

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private CouponRepository couponRepository;

    @InjectMocks
    private IdempotencyKeyService idempotencyKeyService;

    @Test
    void 신규_키면_새로_생성하고_PROCEED를_반환한다() {
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.empty());
        when(appUserRepository.getReferenceById(anyLong())).thenReturn(mock(AppUser.class));
        when(couponRepository.getReferenceById(anyLong())).thenReturn(mock(Coupon.class));

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        when(idempotencyKeyRepository.save(captor.capture())).thenAnswer(invocation -> {
            IdempotencyKey saved = invocation.getArgument(0);
            setId(saved, 10L);
            return saved;
        });

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.PROCEED);
        assertThat(decision.recordId()).isEqualTo(10L);
        assertThat(captor.getValue().getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void 처리중이고_만료_안됐으면_CONFLICT를_반환한다() {
        IdempotencyKey record = existing(IdempotencyStatus.IN_PROGRESS, LocalDateTime.now().plusSeconds(30), null, null);
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(record));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.CONFLICT);
    }

    @Test
    void 처리중이고_만료됐으면_재사용하고_PROCEED를_반환한다() {
        IdempotencyKey record = existing(IdempotencyStatus.IN_PROGRESS, LocalDateTime.now().minusSeconds(1), null, null);
        setId(record, 20L);
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(record));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.PROCEED);
        assertThat(decision.recordId()).isEqualTo(20L);
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.isExpired(LocalDateTime.now())).isFalse();
    }

    @Test
    void 완료된_SUCCEEDED_키면_저장된_응답을_그대로_REPLAY한다() {
        IdempotencyKey record = existing(IdempotencyStatus.SUCCEEDED, LocalDateTime.now().plusSeconds(30), 200, "{\"isSuccess\":true}");
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(record));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.REPLAY);
        assertThat(decision.replayStatus()).isEqualTo(200);
        assertThat(decision.replayBody()).isEqualTo("{\"isSuccess\":true}");
    }

    @Test
    void 응답이_저장된_FAILED_키면_그대로_REPLAY한다() {
        IdempotencyKey record = existing(IdempotencyStatus.FAILED, LocalDateTime.now().plusSeconds(30), 409, "{\"isSuccess\":false}");
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(record));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.REPLAY);
        assertThat(decision.replayStatus()).isEqualTo(409);
    }

    @Test
    void 응답없는_FAILED_키면_Redis_예외로_끊긴_시도로_보고_재처리를_허용한다() {
        IdempotencyKey record = existing(IdempotencyStatus.FAILED, LocalDateTime.now().plusSeconds(30), null, null);
        setId(record, 30L);
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(record));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.PROCEED);
        assertThat(decision.recordId()).isEqualTo(30L);
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void 같은_키인데_요청내용이_다르면_KEY_REUSED를_반환한다() {
        // couponId가 200L인 요청으로 만든 키 — 아래에서 COUPON_ID(100L)로 재요청하면 해시가 달라짐
        IdempotencyKey record = existingForCoupon(200L, IdempotencyStatus.IN_PROGRESS, LocalDateTime.now().plusSeconds(30));
        when(idempotencyKeyRepository.findByUser_UserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(record));

        IdempotencyDecision decision = idempotencyKeyService.begin(USER_ID, COUPON_ID, KEY);

        assertThat(decision.type()).isEqualTo(IdempotencyDecision.Type.KEY_REUSED);
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
