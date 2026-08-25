package com.mycom.petcoupon.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponUpdateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponUpdateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {
	private final EventRepository eventRepository;
	private final CouponRepository couponRepository;
	private final CouponStockRepository couponStockRepository;
	private final CouponConverter couponConverter;

	@Override
	@Transactional
	public CouponCreateResponse createCoupon(Long eventId, CouponCreateRequest request) {
		Event event = eventRepository.findById(eventId)
				.orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		validateEventStatus(event);
		validateIssuePeriod(event, request.issueStartAt(), request.issueEndAt());
		validateDiscountPolicy(request.discountType(), request.discountValue(), request.maxDiscountAmount());

		Coupon coupon = couponConverter.toCoupon(event, request);
		Coupon savedCoupon = couponRepository.save(coupon);
		CouponStock couponStock = couponConverter.toCouponStock(savedCoupon, request);
		CouponStock savedCouponStock = couponStockRepository.save(couponStock);

		return couponConverter.toCreateResponse(savedCoupon, savedCouponStock);
	}

	@Override
	@Transactional
	public CouponUpdateResponse updateCoupon(Long eventId, Long couponId, CouponUpdateRequest request) {
		if (request.isEmpty()) {
			throw new GeneralException(CouponErrorCode.EMPTY_UPDATE_REQUEST);
		}

		Coupon coupon = findCouponInEvent(eventId, couponId);
		CouponStock couponStock = couponStockRepository.findById(couponId)
				.orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));
		Event event = coupon.getEvent();

		validateEventStatusForUpdate(event);
		validateCouponStatusForUpdate(coupon);

		String name = resolve(request.name(), coupon.getName());
		DiscountType discountType = resolve(request.discountType(), coupon.getDiscountType());

		// 정액 할인에 최대 할인 금액을 "직접 실어 보낸" 요청은 그 자체가 모순이라 거부한다.
		// 기존값에서 넘어온 maxDiscountAmount는 resolveMaxDiscountAmount가 조용히 버리는데,
		// 그건 RATE -> FIXED_AMOUNT 타입 전환을 막지 않기 위해서다(그때는 요청값이 null이다).
		validateFixedAmountHasNoMaxDiscountAmount(discountType, request.maxDiscountAmount());

		int discountValue = resolve(request.discountValue(), coupon.getDiscountValue());
		int minOrderAmount = resolve(request.minOrderAmount(), coupon.getMinOrderAmount());
		Integer maxDiscountAmount = resolveMaxDiscountAmount(
				discountType,
				request.maxDiscountAmount(),
				coupon.getMaxDiscountAmount()
		);
		LocalDateTime issueStartAt = resolve(request.issueStartAt(), coupon.getIssueStartAt());
		LocalDateTime issueEndAt = resolve(request.issueEndAt(), coupon.getIssueEndAt());
		int validDays = resolve(request.validDays(), coupon.getValidDays());

		validateIssuePeriod(event, issueStartAt, issueEndAt);
		validateDiscountPolicy(discountType, discountValue, maxDiscountAmount);

		if (request.totalQuantity() != null) {
			updateTotalQuantity(couponStock, request.totalQuantity());
		}

		coupon.updatePolicy(
				name,
				discountType,
				discountValue,
				minOrderAmount,
				maxDiscountAmount,
				issueStartAt,
				issueEndAt,
				validDays
		);

		// @LastModifiedDate는 flush 시점(@PreUpdate)에 채워진다. 여기서 flush하지 않으면
		// 방금 수정한 건인데도 직전 updatedAt이 응답에 실린다. flush는 영속성 컨텍스트 전체를
		// 대상으로 하므로 couponStock의 updatedAt도 함께 갱신된다.
		couponRepository.flush();

		return couponConverter.toUpdateResponse(coupon, couponStock);
	}

	private Coupon findCouponInEvent(Long eventId, Long couponId) {
		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));

		if (!coupon.getEvent().getEventId().equals(eventId)) {
			throw new GeneralException(CouponErrorCode.COUPON_NOT_FOUND);
		}

		return coupon;
	}

	private void updateTotalQuantity(CouponStock couponStock, int totalQuantity) {
		if (couponStock.getIssuedQuantity() != 0) {
			throw new GeneralException(CouponErrorCode.TOTAL_QUANTITY_UPDATE_NOT_ALLOWED);
		}

		couponStock.updateTotalQuantity(totalQuantity);
	}

	private <T> T resolve(T requestValue, T existingValue) {
		return requestValue != null ? requestValue : existingValue;
	}

	private void validateFixedAmountHasNoMaxDiscountAmount(DiscountType discountType, Integer requestedMaxDiscountAmount) {
		if (discountType == DiscountType.FIXED_AMOUNT && requestedMaxDiscountAmount != null) {
			throw new GeneralException(CouponErrorCode.INVALID_FIXED_AMOUNT_DISCOUNT_POLICY);
		}
	}

	private Integer resolveMaxDiscountAmount(DiscountType discountType, Integer requestValue, Integer existingValue) {
		if (discountType == DiscountType.FIXED_AMOUNT) {
			return null;
		}

		return resolve(requestValue, existingValue);
	}

	private void validateEventStatus(Event event) {
		if (event.getStatus() != EventStatus.SCHEDULED) {
			throw new GeneralException(CouponErrorCode.INVALID_EVENT_STATUS);
		}
	}

	private void validateEventStatusForUpdate(Event event) {
		if (event.getStatus() != EventStatus.SCHEDULED) {
			throw new GeneralException(CouponErrorCode.INVALID_EVENT_STATUS_FOR_UPDATE);
		}
	}

	private void validateCouponStatusForUpdate(Coupon coupon) {
		if (coupon.getStatus() != CouponStatus.READY) {
			throw new GeneralException(CouponErrorCode.INVALID_COUPON_STATUS_FOR_UPDATE);
		}
	}

	private void validateIssuePeriod(Event event, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
		if (!issueEndAt.isAfter(issueStartAt)) {
			throw new GeneralException(CouponErrorCode.INVALID_ISSUE_PERIOD);
		}

		if (issueStartAt.isBefore(event.getOpenAt()) || issueEndAt.isAfter(event.getCloseAt())) {
			throw new GeneralException(CouponErrorCode.ISSUE_PERIOD_OUT_OF_EVENT_PERIOD);
		}
	}

	private void validateDiscountPolicy(DiscountType discountType, int discountValue, Integer maxDiscountAmount) {
		if (discountType == DiscountType.RATE
				&& (discountValue > 100
						|| (maxDiscountAmount != null && maxDiscountAmount <= 0))) {
			throw new GeneralException(CouponErrorCode.INVALID_RATE_DISCOUNT_POLICY);
		}

		if (discountType == DiscountType.FIXED_AMOUNT && maxDiscountAmount != null) {
			throw new GeneralException(CouponErrorCode.INVALID_FIXED_AMOUNT_DISCOUNT_POLICY);
		}
	}
}
