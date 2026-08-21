package com.mycom.petcoupon.internal.service;

import com.mycom.petcoupon.internal.dto.req.CouponResetRequest;
import com.mycom.petcoupon.internal.dto.res.CouponResetResponse;

public interface InternalCouponResetService {

	/** 쿠폰 하나의 발급 관련 데이터를 모두 지우고 재고를 초기 상태로 되돌린다. */
	CouponResetResponse reset(Long couponId, CouponResetRequest request);
}
