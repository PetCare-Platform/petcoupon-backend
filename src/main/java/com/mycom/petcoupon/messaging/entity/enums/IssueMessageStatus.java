package com.mycom.petcoupon.messaging.entity.enums;

import java.util.Set;

public enum IssueMessageStatus {
	PENDING,
	SENT,
	CONSUMED,
	FAILED,
	DLQ,
	// 관리자가 DLQ 메시지를 재처리(reprocess)로 선점한 동안의 임시 상태(#217).
	// claimForReprocess가 DLQ에서 이 상태로 전이시키고, 발행 결과에 따라 SENT 또는 DLQ로
	// 다시 정리된다. markSent가 DLQ를 직접 SENT로 올리는 걸 막기 위한 상태다 — claim 없이
	// 걸려온 markSent(예: Outbox Poller의 지연된 중복 발행 콜백)는 status가 DLQ인 채라
	// 조건에 안 걸려 무시되고, 진짜 재처리로 REPROCESSING이 된 건만 SENT로 넘어갈 수 있다.
	REPROCESSING,
	// 관리자가 DLQ 메시지를 재처리하지 않기로 포기하고 재고까지 복구한 최종 상태.
	// FAILED로 재사용하면 Outbox Poller(findByStatusInAndRetryCountLessThan)가 다시 집어서
	// 이미 복구한 재고를 또 소진시키며 재발행해버리므로 별도 상태가 필요하다.
	ABANDONED;

	// "아직 확정 안 됨" 상태 목록 — 여러 곳에 리터럴로 흩어져 있다가 REPROCESSING 추가를 놓친
	// 적이 있어(#217 후속 리뷰) 자바 코드는 이 상수를 재사용한다.
	public static final Set<IssueMessageStatus> IN_PROGRESS_STATUSES = Set.of(PENDING, SENT, FAILED, REPROCESSING);
}
