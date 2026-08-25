package com.mycom.petcoupon.messaging.service;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueCodeGenerator;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.messaging.entity.IssueMessage;
import com.mycom.petcoupon.messaging.repository.IssueMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueOutboxServiceImpl implements CouponIssueOutboxService {

	private final CouponRepository couponRepository;
	private final IssueMessageRepository issueMessageRepository;
	private final JsonMapper jsonMapper;
	private final CouponIssueCodeGenerator couponIssueCodeGenerator;
	
	@Override
	public void saveIfAbsent(Long couponId, Long userId, String requestId, long sequenceNo) {
		String topic = KafkaTopics.COUPON_ISSUE_EVENT;

        if (issueMessageRepository.existsByTopicAndMessageKey(topic, requestId)) return;

        try {
            Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

            String couponCode = couponIssueCodeGenerator.generate();
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(coupon.getValidDays());

            CouponIssueEvent event = new CouponIssueEvent(
                couponId,
                userId,
                requestId,
                sequenceNo,
                couponCode,
                expiresAt
            );

            String payload = jsonMapper.writeValueAsString(event);

            issueMessageRepository.saveAndFlush(
                IssueMessage.pending(
                    coupon,
                    userId,
                    sequenceNo,
                    requestId,
                    payload
                )
            );
            
        } catch (DataIntegrityViolationException e) {
        	
            // 동시 재처리 중 다른 Consumer가 먼저 저장한 경우
            if (issueMessageRepository.existsByTopicAndMessageKey(topic, requestId)) return;
            throw e;
            
        } catch (GeneralException e) {
        	
            throw e;
        } catch (Exception e) {
        	log.error(
        		"쿠폰 발급 Outbox 저장 실패. couponId={}, userId={}, requestId={}, sequenceNo={}",
        		couponId,
        		userId,
        		requestId,
        		sequenceNo,
        		e
        	);
        	
            throw new GeneralException(CouponErrorCode.ISSUE_OUTBOX_SAVE_FAILED);
        }
    }

}
