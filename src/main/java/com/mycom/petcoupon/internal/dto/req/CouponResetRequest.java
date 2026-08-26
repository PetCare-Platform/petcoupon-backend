package com.mycom.petcoupon.internal.dto.req;

import jakarta.validation.constraints.Positive;

/**
 * 부하 테스트 데이터 초기화 요청.
 *
 * <p>{@code totalQuantity} 는 선택값이다. 값을 주면 총재고까지 함께 바꾼다.
 * 재고 규모가 달라질 때마다 쿠폰을 새로 만들지 않고 쿠폰 하나로
 * 스모크(10) → 기본(100) → 중간(500) → 최대(1,000) → 최종(10,000) 을 모두 돌리기 위함이다.
 * 값이 없으면 기존 총재고를 유지한 채 발급 수량만 되돌린다.
 *
 * <p>{@code force} 는 앞 회차 메시지가 남아 있어도 초기화를 강행한다. 기본값은 {@code false} 로,
 * 남아 있으면 409 로 거절한다. 되찾을 수 없는 pending 메시지처럼 사람이 판단해 넘겨야 하는
 * 경우에만 쓴다. 켜고 돌린 회차의 결과는 신뢰할 수 없다.
 */
public record CouponResetRequest(
		@Positive(message = "총재고는 0보다 커야 합니다.")
		Integer totalQuantity,

		Boolean force
) {

	public boolean isForced() {
		return Boolean.TRUE.equals(force);
	}
}
