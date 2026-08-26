package com.mycom.petcoupon.messaging.entity.enums;

public enum IssueMessageStatus {
	PENDING,
	SENT,
	CONSUMED,
	FAILED,
	DLQ,
	// 관리자가 DLQ 메시지를 재처리하지 않기로 포기하고 재고까지 복구한 최종 상태.
	// FAILED로 재사용하면 Outbox Poller(findByStatusInAndRetryCountLessThan)가 다시 집어서
	// 이미 복구한 재고를 또 소진시키며 재발행해버리므로 별도 상태가 필요하다.
	ABANDONED
}
