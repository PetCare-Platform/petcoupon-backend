package com.mycom.petcoupon.coupon.issue.config;

// 쿠폰 발급 파이프라인이 쓰는 Redis 키 포맷. CouponIssueLuaServiceImpl(Lua 실행)과
// ReconciliationServiceImpl(정합성 검증) 둘 다 같은 키를 참조해야 해서 공유 유틸로 뺐다.
public final class CouponIssueRedisKeys {

	private CouponIssueRedisKeys() {
	}

	public static String stock(Long couponId) {
		return key("stock", couponId);
	}

	public static String applicants(Long couponId) {
		return key("applicants", couponId);
	}

	public static String sequence(Long couponId) {
		return key("sequence", couponId);
	}

	public static String requestSequence(Long couponId) {
		return key("request-sequence", couponId);
	}

	private static String key(String suffix, Long couponId) {
		return "coupon:issue:" + suffix + ":{" + couponId + "}";
	}
}
