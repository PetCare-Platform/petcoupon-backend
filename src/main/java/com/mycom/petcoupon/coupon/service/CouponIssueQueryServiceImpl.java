package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponIssueConverter;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDetailResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueRequestResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueStatusResponse;
import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyStatusResult;
import com.mycom.petcoupon.user.exception.UserErrorCode;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueQueryServiceImpl implements CouponIssueQueryService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueConverter couponIssueConverter;
    private final AppUserRepository appUserRepository;
    private final IdempotencyKeyService idempotencyKeyService;


    @Override
    @Transactional(readOnly = true)
    public CouponIssueStatusResponse getStatus(Long couponIssueId) {

        CouponIssue couponIssue = couponIssueRepository.findById(couponIssueId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_ISSUE_NOT_FOUND));

        boolean isUsable = couponIssue.getStatus() == IssueStatus.ISSUED
                && couponIssue.getExpiresAt().isAfter(LocalDateTime.now());

        return couponIssueConverter.toStatusResponse(couponIssue, isUsable);
    }

    @Override
    @Transactional(readOnly = true)
    public CouponIssueDetailResponse getDetail(Long couponIssueId){
        CouponIssue couponIssue = couponIssueRepository.findById(couponIssueId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_ISSUE_NOT_FOUND));

        boolean isUsable = couponIssue.getStatus() == IssueStatus.ISSUED && couponIssue.getExpiresAt().isAfter(LocalDateTime.now());
        
        return couponIssueConverter.toDetailResponse(couponIssue, isUsable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponIssueRequestResponse> getIssueRequests(Long userId) {

        // userId 자체가 존재하지 않는 사용자면 404로 먼저 걸러냄 (존재하는 유저인데 발급 내역이 0건인 경우와 구분)
        if (!appUserRepository.existsById(userId)) {
            throw new GeneralException(UserErrorCode.USER_NOT_FOUND);
        }

        // repository에서 이미 coupon fetch join + createdAt 내림차순 정렬까지 끝낸 상태로 가져오므로
        // 여기선 엔티티 리스트를 DTO 리스트로 변환만 하면 됨
        return couponIssueRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(couponIssueConverter::toRequestResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public IdempotencyKeyStatusResult getRequestStatus(Long userId, String idempotencyKey) {
        // userId 자체가 존재하지 않는 사용자면 404로 먼저 걸러냄 (getIssueRequests와 동일한 이유)
        if (!appUserRepository.existsById(userId)) {
            throw new GeneralException(UserErrorCode.USER_NOT_FOUND);
        }

        return idempotencyKeyService.findStatus(userId, idempotencyKey);
    }
}