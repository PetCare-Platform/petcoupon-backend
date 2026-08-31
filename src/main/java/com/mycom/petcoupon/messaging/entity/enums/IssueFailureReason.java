package com.mycom.petcoupon.messaging.entity.enums;

// 실패 사유 분류 집계(#195)용 — issue_message가 DLQ로 빠지는 발생 지점은 코드상 이 둘뿐이다
// (CouponIssueEventProducer.markFailed / CouponIssueEventRecoverer.markDlq). lastError는 그
// 지점에서 터진 예외 메시지 자유 텍스트라 사유별 집계에 못 쓰므로, 지점 자체를 사유로 남긴다.
// CouponIssueReprocessRecoveryScheduler는 새 사유를 안 만든다 — 원래 사유를 덮어쓰면
// countPublishedByCoupon()이 이미 발행된 건을 미발행으로 잘못 세기 때문이다(#217 후속 리뷰).
public enum IssueFailureReason {
	// Outbox -> Kafka 발행 실패 (CouponIssueEventProducer)
	KAFKA_PUBLISH_FAILED,
	// Kafka Consumer 처리(재고 확정/coupon_issue 저장 등) 실패, 재시도 소진 후 DLQ (CouponIssueEventRecoverer)
	CONSUME_PROCESSING_FAILED
}
