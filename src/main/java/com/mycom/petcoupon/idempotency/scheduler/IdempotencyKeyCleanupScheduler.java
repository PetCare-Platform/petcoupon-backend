package com.mycom.petcoupon.idempotency.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 매일 새벽 4시에 보관기간이 지난 idempotency_key 행을 정리한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyKeyCleanupScheduler {

    private final IdempotencyKeyService idempotencyKeyService;

    @Scheduled(cron = "0 0 4 * * *")
    public void cleanup() {
        int deletedCount = idempotencyKeyService.cleanupExpiredRecords();
        log.info("만료된 IdempotencyKey {}건을 정리했습니다.", deletedCount);
    }
}
