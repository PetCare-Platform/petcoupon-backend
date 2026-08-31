package com.mycom.petcoupon.coupon.issue.service;

import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueStockRestoreResult;

public interface CouponIssueLuaService {

	CouponIssueLuaResult issue(Long couponId, Long userId, String requestId);

	void clearIssueState(Long couponId);

	/**
	 * 발급 상태를 지우고 재고를 {@code totalQuantity} 로 다시 세운다(부하 테스트 초기화 전용).
	 *
	 * <p>재고 키 포맷을 호출부에 노출하지 않으려고 삭제 · 세팅 · 확인을 한 곳에서 처리한다.
	 * 반환값은 세팅한 값이 아니라 <b>실제로 저장된 값을 다시 읽은 것</b>이라 검증에 쓸 수 있다.
	 * 키가 없으면 {@code null} 이고 초기화가 끝나지 않았다는 뜻이다.
	 */
	Integer resetIssueState(Long couponId, int totalQuantity);

	CouponIssueRealtimeStock getRealtimeStock(Long couponId);
	
	CouponIssueStockRestoreResult restoreStock(Long couponId, Long userId, String requestId, Long sequenceNo);
}
