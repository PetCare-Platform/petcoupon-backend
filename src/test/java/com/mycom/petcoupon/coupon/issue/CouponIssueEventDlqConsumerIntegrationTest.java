package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

import com.mycom.petcoupon.coupon.issue.config.KafkaTopics;
import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueEventDlqConsumer;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * CouponIssueEventDlqConsumer는 containerFactory를 지정하지 않고 Spring Boot 기본 컨테이너
 * 팩토리로 fallback한다 — 이게 실제 Kafka로 한 번도 end-to-end 검증된 적이 없었다(#112).
 * 단위 테스트(consumer.consume(event) 직접 호출)는 리스너 컨테이너/역직렬화 배선을 아예 안 타서
 * 이 fallback이 진짜 동작하는지 증명하지 못한다 — 실제 Docker Kafka에 DLQ 토픽으로 메시지를
 * 발행해서 리스너가 정말 수신하는지 확인한다.
 *
 * Mockito Spy로 빈을 감싸서 호출 검증하는 방식은 @KafkaListener 엔드포인트가 컨텍스트 초기화
 * 시점에 원본 빈 인스턴스를 직접 참조해버려 스파이 대체 타이밍과 어긋날 수 있어(오탐 위험),
 * 실제 실행 결과(로그)를 직접 캡처하는 방식을 씀.
 */
@SpringBootTest
class CouponIssueEventDlqConsumerIntegrationTest {

	@Autowired
	private KafkaTemplate<String, Object> kafkaTemplate;

	private ListAppender<ILoggingEvent> logAppender;

	@BeforeEach
	void setUp() {
		logAppender = new ListAppender<>();
		logAppender.start();
		((Logger) LoggerFactory.getLogger(CouponIssueEventDlqConsumer.class)).addAppender(logAppender);
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(CouponIssueEventDlqConsumer.class)).detachAppender(logAppender);
	}

	@Test
	void DLQ_토픽에_발행된_메시지를_기본_컨테이너_팩토리로_수신해서_로그를_남긴다() {
		String requestId = "dlq-verify-" + System.currentTimeMillis();
		CouponIssueEvent event = new CouponIssueEvent(
			1L, 10L, requestId, 1L, "CODE", LocalDateTime.now().plusDays(7)
		);

		kafkaTemplate.send(KafkaTopics.COUPON_ISSUE_EVENT_DLQ, requestId, event);

		// petcoupon-dlq는 이 테스트에서 처음 생기는 컨슈머 그룹이라, 최초 JoinGroup/rebalance에
		// 몇 초 이상 걸릴 수 있어 여유 있게 잡음 (실측: 10초로는 부족, group-id 분리 자체는
		// kafka-consumer-groups.sh --describe로 별도 그룹 생성/오프셋 커밋 확인함)
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
			assertThat(logAppender.list)
				.extracting(ILoggingEvent::getFormattedMessage)
				.anyMatch(message -> message.contains(requestId))
		);
	}
}
