package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueQueryServiceImpl implements CouponIssueQueryService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueConverter couponIssueConverter;

    @Override
    @Transactional(readOnly = true)
    public CouponIssueStatusResponse getStatus(Long couponIssueId) {

        CouponIssue couponIssue = couponIssueRepository.findById(couponIssueId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_ISSUE_NOT_FOUND));

        boolean isUsable = couponIssue.getStatus() == IssueStatus.ISSUED
                && couponIssue.getExpiresAt().isAfter(LocalDateTime.now());

        return couponIssueConverter.toStatusResponse(couponIssue, isUsable);
    }
}