package com.mycom.petcoupon.coupon.issue.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class UuidCouponIssueCodeGenerator implements CouponIssueCodeGenerator {

	@Override
    public String generate() {
        return UUID.randomUUID()
            .toString()
            .replace("-", "");
    }
}
