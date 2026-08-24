package com.mycom.petcoupon.messaging.publisher;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.producer.CouponIssueEventProducer;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
	prefix = "coupon.issue.outbox",  
	name = "enabled",   
	havingValue = "true", 
	matchIfMissing = true
)
public class CouponIssueOutboxPublisher {
	
	private final IssueMessageRepository issueMessageRepository;
    private final CouponIssueEventProducer couponIssueEventProducer;

    @Value("${coupon.issue.outbox.max-retry-count:5}")
    private int maxRetryCount;
    
    @Value("${coupon.issue.outbox.batch-size:100}")
    private int batchSize;

    @Scheduled(
        fixedDelayString = "${coupon.issue.outbox.publish-fixed-delay-ms:1000}",
        scheduler = "outboxTaskScheduler"
    )
    public void publishPendingMessages() {
        try {
        	Pageable pageable = PageRequest.of(
        		0,
        		batchSize,
        		Sort.by(Sort.Direction.ASC, "messageId")
        	);
        	
            List<IssueMessage> messages =
                issueMessageRepository
                    .findByStatusInAndRetryCountLessThan(
                        List.of(
                            IssueMessageStatus.PENDING,
                            IssueMessageStatus.FAILED
                        ),
                        maxRetryCount,
                        pageable
                    );

            if (messages.isEmpty()) {
                return;
            }

            log.info(
                "[CouponIssueOutbox] Kafka 발행 대상 조회. count={}",
                messages.size()
            );

            CompletableFuture<?>[] futures = messages.stream()
                .map(message ->
                    couponIssueEventProducer.publish(message)
                        .exceptionally(exception -> {
                            log.warn(
                                "[CouponIssueOutbox] 발행 실패. messageId={}",
                                message.getMessageId(),
                                exception
                            );
                            return null;
                        })
                )
                .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).join();

        } catch (Exception e) {
            log.error(
                "[CouponIssueOutbox] Outbox 발행 작업 중 예외 발생",
                e
            );
        }
    }
}
