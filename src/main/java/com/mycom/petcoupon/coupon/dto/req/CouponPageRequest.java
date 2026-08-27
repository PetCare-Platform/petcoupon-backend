package com.mycom.petcoupon.coupon.dto.req;

import java.util.Set;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

public record CouponPageRequest(
        int page,
        int size
) {
    public static final String DEFAULT_PAGE = "0";
    public static final String DEFAULT_SIZE = "20";

    private static final Set<Integer> ALLOWED_SIZES = Set.of(10, 20, 50, 100);

    public CouponPageRequest {
        if (page < 0 || !ALLOWED_SIZES.contains(size)) {
            throw new GeneralException(CouponErrorCode.INVALID_COUPON_PAGE_REQUEST);
        }
    }

    public static CouponPageRequest from(String page, String size) {
        try {
            return new CouponPageRequest(Integer.parseInt(page), Integer.parseInt(size));
        } catch (NumberFormatException e) {
            throw new GeneralException(CouponErrorCode.INVALID_COUPON_PAGE_REQUEST);
        }
    }
}
