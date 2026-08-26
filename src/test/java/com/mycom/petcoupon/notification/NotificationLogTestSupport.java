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
		deleteByCouponIds(entityManager, List.of(couponId));
	}

	public static void deleteByCouponIds(EntityManager entityManager, Collection<Long> couponIds) {
		entityManager.createNativeQuery(
				"DELETE n FROM notification_log n JOIN coupon_issue ci ON n.coupon_issue_id = ci.coupon_issue_id WHERE ci.coupon_id IN :couponIds")
				.setParameter("couponIds", couponIds)
				.executeUpdate();
	}
}
