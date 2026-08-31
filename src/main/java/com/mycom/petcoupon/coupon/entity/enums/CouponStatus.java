package com.mycom.petcoupon.coupon.entity.enums;

import java.util.List;
import java.util.Set;

public enum CouponStatus {
	READY,
	ACTIVE,
	SOLD_OUT,
	ENDED;

	// 정합성 검증을 실행할 수 있는 상태(#202). 기준은 "발급 기간이 끝났는가"가 아니라
	// "더 이상 발급될 수 없는가"다 — SOLD_OUT은 재고가 0이라 Lua가 전건 거절하고,
	// ENDED는 발급 기간이 지났다. 판정과 순회 대상이 갈리면 스케줄러가 도는 쿠폰과
	// Tasklet이 통과시키는 쿠폰이 어긋나므로 목록을 여기 한곳에 둔다.
	private static final Set<CouponStatus> RECONCILABLE = Set.of(SOLD_OUT, ENDED);

	// 스케줄러 조회(findCouponIdsByStatusIn)에 그대로 넘길 수 있게 List로도 노출한다.
	public static final List<CouponStatus> RECONCILABLE_STATUSES = List.of(SOLD_OUT, ENDED);

	public static boolean isReconcilable(CouponStatus status) {
		return RECONCILABLE.contains(status);
	}
}
