package com.mycom.petcoupon.coupon.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDetailResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueRequestResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.CouponIssue;

/**
 * Entity <-> DTO 변환 전담 클래스.
 * 나중에 CouponIssue 엔티티로 발급 생성을 저장하게 되면 toCreateResponse(CouponIssue)로 바꾸면 됨.
 */
@Component
public class CouponIssueConverter {

    public CouponIssueCreateResponse toCreateResponse(Long couponId, Long userId) {
        return CouponIssueCreateResponse.builder()
                .couponId(couponId)
                .userId(userId)
                .build();
    }

    public CouponIssueStatusResponse toStatusResponse(CouponIssue couponIssue, boolean isUsable) {
        return CouponIssueStatusResponse.builder()
                .status(couponIssue.getStatus().name())
                .isUsable(isUsable)
                .expiresAt(couponIssue.getExpiresAt())
                .build();
    }

    public CouponIssueDetailResponse toDetailResponse(CouponIssue couponIssue, boolean isUsable) {
        return CouponIssueDetailResponse.builder()
                .couponIssueId(couponIssue.getCouponIssueId())
                .couponCode(couponIssue.getCouponCode())
                .status(couponIssue.getStatus().name())
                .isUsable(isUsable)
                .usedAt(couponIssue.getUsedAt())
                .expiresAt(couponIssue.getExpiresAt())
                .createdAt(couponIssue.getCreatedAt())
                .build();
    }

    // 발급 내역 목록 1건 변환. issuedAt은 별도 컬럼이 없어서 BaseEntity의 createdAt(row 생성 시각)을 그대로 씀.
    // request_id/sequence_no는 내부 재시도용 값이라 응답에 안 넣음(이슈 #36 참고 사항).
    public CouponIssueRequestResponse toRequestResponse(CouponIssue couponIssue) {
        return CouponIssueRequestResponse.builder()
                .couponIssueId(couponIssue.getCouponIssueId())
                .couponId(couponIssue.getCoupon().getCouponId())
                .couponName(couponIssue.getCoupon().getName())
                .couponCode(couponIssue.getCouponCode())
                .status(couponIssue.getStatus().name())
                .issuedAt(couponIssue.getCreatedAt())
                .usedAt(couponIssue.getUsedAt())
                .expiresAt(couponIssue.getExpiresAt())
                .build();
    }
}
