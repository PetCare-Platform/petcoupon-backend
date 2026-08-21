package com.mycom.petcoupon.coupon.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponIssueHistory;
import com.mycom.petcoupon.coupon.entity.enums.HistoryActorType;
import com.mycom.petcoupon.coupon.entity.enums.IssueHistoryStatus;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponIssueHistoryRepository;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueCancelServiceImpl implements CouponIssueCancelService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueHistoryRepository couponIssueHistoryRepository;

    @Override
    @Transactional
    public void cancelUsage(Long couponIssueId, Long userId) {

        CouponIssue couponIssue = couponIssueRepository.findById(couponIssueId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_ISSUE_NOT_FOUND));

        if (!couponIssue.getUser().getUserId().equals(userId)) {
            throw new GeneralException(CouponErrorCode.NOT_COUPON_OWNER);
        }

        Long couponId = couponIssue.getCoupon().getCouponId();

        int updatedRows = couponIssueRepository.cancelUsageIfMatches(
                couponIssueId, IssueStatus.USED, IssueStatus.ISSUED
        );

        if (updatedRows == 0) {
            throw new GeneralException(CouponErrorCode.INVALID_ISSUE_STATUS);
        }

        CouponIssueHistory history = CouponIssueHistory.builder()
                .couponIssue(couponIssue)
                .couponId(couponId)
                .userId(userId)
                .fromStatus(IssueHistoryStatus.USED)
                .toStatus(IssueHistoryStatus.ISSUED)
                .actorType(HistoryActorType.USER)
                .actorId(userId)
                .build();

        couponIssueHistoryRepository.save(history);
    }
}
