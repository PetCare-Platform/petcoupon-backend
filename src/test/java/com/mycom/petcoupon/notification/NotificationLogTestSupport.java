package com.mycom.petcoupon.notification;

import java.util.Collection;
import java.util.List;

import jakarta.persistence.EntityManager;

// notification_log가 coupon_issue를 FK로 물고 있어, 통합 테스트 tearDown에서 coupon_issue를
// 지우기 전에 항상 먼저 지워야 한다 — 여러 테스트 파일에 복붙돼있던 걸 공용화.
public final class NotificationLogTestSupport {

	private NotificationLogTestSupport() {
	}

	public static void deleteByCouponId(EntityManager entityManager, Long couponId) {
		deleteByCouponIds(entityManager, couponId == null ? List.of() : List.of(couponId));
	}

	// null/빈 컬렉션 가드를 여기 한 곳에 둔다 — 대부분의 tearDownData()가 이 메서드를 첫 줄로
	// 호출하므로, 여기서 막지 않으면 setUp 실패로 couponId가 null일 때 그 뒤에 이어지는
	// coupon_issue/coupon_stock/coupon/app_user 삭제까지 통째로 중단돼 행이 남는다.
	public static void deleteByCouponIds(EntityManager entityManager, Collection<Long> couponIds) {
		if (couponIds == null || couponIds.isEmpty()) {
			return;
		}
		entityManager.createNativeQuery(
				"DELETE n FROM notification_log n JOIN coupon_issue ci ON n.coupon_issue_id = ci.coupon_issue_id WHERE ci.coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
	}
}
