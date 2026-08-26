package com.mycom.petcoupon.coupon.issue.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;

import lombok.extern.slf4j.Slf4j;

// containerFactory를 지정하지 않고 Spring Boot 기본 컨테이너 팩토리를 사용함 —
// couponIssueKafkaListenerContainerFactory(재시도 + CouponIssueEventRecoverer)를 그대로 쓰면
// 여기서 처리 실패 시 같은 Recoverer가 이 DLQ 토픽에 다시 발행해 무한 루프에 빠질 수 있음
@Slf4j
@Component
public class CouponIssueEventDlqConsumer {

	@KafkaListener(topics = KafkaTopics.COUPON_ISSUE_EVENT_DLQ)
	public void consume(CouponIssueEvent event) {
		log.error(
			"[CouponIssueEvent] DLQ 메시지 수신 (수동 확인 필요, issue_message.status=DLQ 참조): "
				+ "couponId={}, userId={}, requestId={}, sequenceNo={}",
			event.couponId(), event.userId(), event.requestId(), event.sequenceNo()
		);
	}
}
