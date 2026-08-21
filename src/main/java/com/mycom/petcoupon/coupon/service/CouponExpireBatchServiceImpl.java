package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponExpireBatchServiceImpl implements CouponExpireBatchService {

    @PersistenceContext
    private EntityManager entityManager;

    private final CouponIssueRepository couponIssueRepository;

    @Override
    @Scheduled(cron = "0 0 1 * * *") // 매일 새벽 1시에 실행
    @Transactional
    public void expireOverdueCoupons() {
        LocalDateTime now = LocalDateTime.now();

        List<Long> targetIds = couponIssueRepository.findIdsToExpire(IssueStatus.ISSUED, now);

        if (targetIds.isEmpty()) {
            log.info("쿠폰 만료 배치: 대상 없음");
            return;
        }

        int historyInserted = entityManager.createNativeQuery("""
                INSERT INTO coupon_issue_history
                    (coupon_issue_id, coupon_id, user_id, from_status, to_status, actor_type, created_at)
                SELECT coupon_issue_id, coupon_id, user_id, 'ISSUED', 'EXPIRED', 'BATCH', NOW(6)
                  FROM coupon_issue
                 WHERE coupon_issue_id IN (:ids)
                """)
                .setParameter("ids", targetIds)
                .executeUpdate();

        int expired = couponIssueRepository.expireByIds(targetIds, IssueStatus.EXPIRED);

        log.info("쿠폰 만료 배치 완료. 대상={}건, 이력기록={}건", expired, historyInserted);
    }
}
