package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mycom.petcoupon.coupon.entity.enums.IssueStatus;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CouponExpireBatchServiceImpl implements CouponExpireBatchService {

    @Value("${coupon.expire.chunk-size:1000}")
    private int chunkSize;

    @PersistenceContext
    private EntityManager entityManager;

    private final CouponIssueRepository couponIssueRepository;
    private final TransactionTemplate transactionTemplate;

    public CouponExpireBatchServiceImpl(
            CouponIssueRepository couponIssueRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.couponIssueRepository = couponIssueRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private record ChunkResult(int fetchedCount, int expiredCount) {}

    @Override
    @Scheduled(cron = "0 0 1 * * *", scheduler = "couponExpireBatchTaskScheduler") // 매일 새벽 1시에 실행
    public int expireOverdueCoupons() {
        LocalDateTime now = LocalDateTime.now();
        int totalExpired = 0;

        while (true) {
            ChunkResult result = transactionTemplate.execute(status -> expireChunk(now));

            totalExpired += result.expiredCount();

            if (result.fetchedCount() < chunkSize) {
                break;
            }
        }

        log.info("쿠폰 만료 배치 완료. 총 대상={}건", totalExpired);
        return totalExpired;
    }

    private ChunkResult expireChunk(LocalDateTime now) {
        List<Long> targetIds = couponIssueRepository.findIdsToExpire(
                IssueStatus.ISSUED, now, PageRequest.of(0, chunkSize)
        );

        if (targetIds.isEmpty()) {
            return new ChunkResult(0, 0);
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

        int expired = couponIssueRepository.expireByIds(targetIds, IssueStatus.ISSUED, IssueStatus.EXPIRED);

        if (expired != historyInserted) {
            log.warn("쿠폰 만료 배치: 상태 변경 건수({})와 이력 기록 건수({})가 다릅니다.", expired, historyInserted);
        }

        return new ChunkResult(targetIds.size(), expired);
    }
}
