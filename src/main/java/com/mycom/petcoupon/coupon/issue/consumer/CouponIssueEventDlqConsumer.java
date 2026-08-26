package com.mycom.petcoupon.coupon.issue.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;

import lombok.extern.slf4j.Slf4j;

// couponIssueKafkaListenerContainerFactory(재시도 + CouponIssueEventRecoverer)를 그대로 쓰면
// 여기서 처리 실패 시 같은 Recoverer가 이 DLQ 토픽에 다시 발행해 무한 루프에 빠질 수 있어서
// 전용 팩토리(couponIssueDlqKafkaListenerContainerFactory)를 씀. 원래는 containerFactory를
// 지정 안 하고 Spring Boot 기본 팩토리로 fallback했었는데, 실제 Kafka로 검증해보니 그 기본
// 팩토리가 JSON 역직렬화 설정이 없는 별개의 ConsumerFactory를 써서 MessageConversionException이
// 났음 (#112) — "기본값으로도 될 것"이라는 가정이 틀렸던 것으로 확인되어 전용 팩토리로 교체함.
// group-id는 메인 Consumer(petcoupon)와 분리 — 모니터링/스케일링을 독립적으로 가져가기 위함 (#112)
@Slf4j
@Component
public class CouponIssueEventDlqConsumer {

	@KafkaListener(
		topics = KafkaTopics.COUPON_ISSUE_EVENT_DLQ,
		containerFactory = "couponIssueDlqKafkaListenerContainerFactory",
		groupId = "${coupon.issue.dlq.consumer.group-id:petcoupon-dlq}"
	)
	public void consume(CouponIssueEvent event) {
		log.error(
			"[CouponIssueEvent] DLQ 메시지 수신 (수동 확인 필요, issue_message.status=DLQ 참조): "
				+ "couponId={}, userId={}, requestId={}, sequenceNo={}",
			event.couponId(), event.userId(), event.requestId(), event.sequenceNo()
		);
	}
}
