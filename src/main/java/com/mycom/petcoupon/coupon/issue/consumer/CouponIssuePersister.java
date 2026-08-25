package com.mycom.petcoupon.coupon.issue.consumer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.entity.CouponIssue;
import com.mycom.petcoupon.coupon.entity.CouponIssueHistory;
import com.mycom.petcoupon.coupon.entity.enums.HistoryActorType;
import com.mycom.petcoupon.coupon.entity.enums.IssueHistoryStatus;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueEvent;
import com.mycom.petcoupon.coupon.repository.CouponIssueHistoryRepository;
import com.mycom.petcoupon.coupon.repository.CouponIssueRepository;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// CouponIssueEventConsumer가 같은 클래스 내부 메서드를 호출하면 프록시를 안 거쳐 @Transactional이 무시되므로
// 별도 빈으로 분리함
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssuePersister {

	private final CouponIssueRepository couponIssueRepository;
	private final CouponRepository couponRepository;
	private final AppUserRepository appUserRepository;
	private final CouponStockRepository couponStockRepository;
	private final CouponIssueHistoryRepository couponIssueHistoryRepository;

	@Transactional
	public void persist(CouponIssueEvent event) {
		CouponIssue couponIssue = couponIssueRepository.saveAndFlush(
			CouponIssue.builder()
				.coupon(couponRepository.getReferenceById(event.couponId()))
				.user(appUserRepository.getReferenceById(event.userId()))
				.sequenceNo(event.sequenceNo())
				.couponCode(event.couponCode())
				.requestId(event.requestId())
				.expiresAt(event.expiresAt())
				.build()
		);

		int updatedRows = couponStockRepository.increaseIssuedQuantity(event.couponId());

		if (updatedRows == 0) {
			throw new IllegalStateException(
				"coupon_stock 갱신 실패(remaining_quantity 부족 또는 coupon_id 없음): couponId=" + event.couponId()
					+ ", requestId=" + event.requestId()
			);
		}

		couponIssueHistoryRepository.save(
			CouponIssueHistory.builder()
				.couponIssue(couponIssue)
				.couponId(event.couponId())
				.userId(event.userId())
				.fromStatus(IssueHistoryStatus.NONE)
				.toStatus(IssueHistoryStatus.ISSUED)
				.actorType(HistoryActorType.SYSTEM)
				.reason("Kafka Consumer 발급 확정")
				.build()
		);

		log.info(
			"[CouponIssueEvent] 저장완료 requestId={} couponIssueId={} sequenceNo={}",
			event.requestId(), couponIssue.getCouponIssueId(), event.sequenceNo()
		);
	}
}
