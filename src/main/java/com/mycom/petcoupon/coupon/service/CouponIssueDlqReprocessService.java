package com.mycom.petcoupon.coupon.service;

import java.util.List;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;

public interface CouponIssueDlqReprocessService {

	List<CouponIssueDlqResponse> listDlqMessages();

	CouponIssueDlqReprocessResponse reprocess(Long messageId);
}
