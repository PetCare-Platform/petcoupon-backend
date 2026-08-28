package com.mycom.petcoupon.coupon.dto.res;

import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;

import lombok.Builder;

/**
 * 파이프라인 소진 상태 응답 DTO.
 *
 * <p>정합성 검증 및 초기화 사전 조건(쿠폰 상태 ENDED + 파이프라인 잔여 소진)을 클라이언트가
 * 파악할 수 있도록 상태를 제공한다.
 *
 * <ul>
 *   <li>{@code blocked}: 파이프라인 잔여가 남아있거나 검사 실패로 차단된 상태인지 여부 ({@code PipelineDrainStatus.isBlocked()}와 동일 기준).</li>
 *   <li>{@code outboxUnconsumed}: <b>해당 쿠폰({@code couponId}) 기준</b> Outbox(issue_message) 미소비 건수(PENDING·SENT·FAILED).</li>
 *   <li>{@code streamUndelivered}: <b>전역(공유 Redis Stream) 기준</b> 미배달 메시지 존재 여부 플래그 (건수가 아닌 0 또는 1). XINFO GROUPS 특성상 건수를 집계할 수 없어 존재 여부만 표시한다.</li>
 *   <li>{@code streamActivePending}: <b>전역(공유 Redis Stream) 기준</b> Consumer Group에 배달되었으나 ACK되지 않은 pending 메시지 실제 건수.</li>
 *   <li>{@code checkFailed}: Redis 통신 실패 등으로 파이프라인 잔여 여부를 알 수 없는 경우 true. true는 잔여 0건이 아니라 "확인 불가"를 뜻한다.</li>
 * </ul>
 */
@Builder
public record CouponPipelineDrainStatusResponse(
        CouponStatus couponStatus,
        boolean blocked,
        long outboxUnconsumed,
        long streamUndelivered,
        long streamActivePending,
        boolean checkFailed
) {}
