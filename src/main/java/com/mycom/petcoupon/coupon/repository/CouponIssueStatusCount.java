package com.mycom.petcoupon.coupon.repository;

import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;

// 대시보드 요약 집계(#172)용 인터페이스 프로젝션.
public interface CouponIssueStatusCount {

	IssueStatus getStatus();

	Long getCount();
}
