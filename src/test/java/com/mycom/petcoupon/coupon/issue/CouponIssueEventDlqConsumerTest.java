package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.coupon.issue.consumer.CouponIssueEventDlqConsumer;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;

class CouponIssueEventDlqConsumerTest {

	private static final CouponIssueEvent EVENT = new CouponIssueEvent(
		1L, 10L, "request-1", 5L, "COUPON-CODE-1", LocalDateTime.now().plusDays(7)
	);

	private final CouponIssueEventDlqConsumer consumer = new CouponIssueEventDlqConsumer();

	@Test
	void DLQ_메시지를_받으면_예외_없이_로그만_남기고_끝난다() {
		assertThatCode(() -> consumer.consume(EVENT)).doesNotThrowAnyException();
	}
}
