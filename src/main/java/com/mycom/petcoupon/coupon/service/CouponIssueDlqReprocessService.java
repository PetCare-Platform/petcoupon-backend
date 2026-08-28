package com.mycom.petcoupon.coupon.service;

import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqAbandonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqPageResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;

public interface CouponIssueDlqReprocessService {

	CouponIssueDlqPageResponse listDlqMessages(CouponPageRequest pageRequest);

	CouponIssueDlqReprocessResponse reprocess(Long messageId);

	CouponIssueDlqAbandonResponse abandon(Long messageId);
}
