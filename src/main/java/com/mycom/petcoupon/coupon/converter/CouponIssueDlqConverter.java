package com.mycom.petcoupon.coupon.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;
import com.mycom.petcoupon.messaging.entity.IssueMessage;

@Component
public class CouponIssueDlqConverter {

	public CouponIssueDlqResponse toDlqResponse(IssueMessage issueMessage) {
		return CouponIssueDlqResponse.builder()
				.messageId(issueMessage.getMessageId())
				.couponId(issueMessage.getCoupon().getCouponId())
				.userId(issueMessage.getUserId())
				.requestId(issueMessage.getMessageKey())
				.retryCount(issueMessage.getRetryCount())
				.lastError(issueMessage.getLastError())
				.createdAt(issueMessage.getCreatedAt())
				.build();
	}

	public CouponIssueDlqReprocessResponse toReprocessResponse(IssueMessage issueMessage) {
		return CouponIssueDlqReprocessResponse.builder()
				.messageId(issueMessage.getMessageId())
				.requestId(issueMessage.getMessageKey())
				.build();
	}
}
