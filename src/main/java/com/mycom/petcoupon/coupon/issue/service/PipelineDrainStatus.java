package com.mycom.petcoupon.coupon.issue.service;

// 상태만 돌려주고 판단(막을지 말지, 무슨 에러코드로 막을지)은 안 한다 — reset()과 reconcile()이
// 이 상태를 어떻게 다룰지는 서로 다르고(에러코드도 다름), 그 결정을 호출자에게 남겨두기 위함이다.
public record PipelineDrainStatus(
        long outboxUnpublished,
        long streamUndelivered,
        boolean checkFailed
) {
    // 검사 자체가 실패했으면 "남은 게 없다"가 아니라 "남았는지 모른다"는 뜻이라 안전하게 true로 본다.
    public boolean isBlocked() {
        return checkFailed || outboxUnpublished > 0 || streamUndelivered > 0;
    }
}
