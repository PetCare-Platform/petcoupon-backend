package com.mycom.petcoupon.coupon.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponCreateResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
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
		validateIssuePeriod(event, request);
		validateDiscountPolicy(request);

		Coupon coupon = couponConverter.toCoupon(event, request);
		Coupon savedCoupon = couponRepository.save(coupon);
		CouponStock couponStock = couponConverter.toCouponStock(savedCoupon, request);
		CouponStock savedCouponStock = couponStockRepository.save(couponStock);

		return couponConverter.toCreateResponse(savedCoupon, savedCouponStock);
	}

	private void validateEventStatus(Event event) {
		if (event.getStatus() != EventStatus.SCHEDULED) {
			throw new GeneralException(CouponErrorCode.INVALID_EVENT_STATUS);
		}
	}

	private void validateIssuePeriod(Event event, CouponCreateRequest request) {
		if (!request.issueEndAt().isAfter(request.issueStartAt())) {
			throw new GeneralException(CouponErrorCode.INVALID_ISSUE_PERIOD);
		}

		if (request.issueStartAt().isBefore(event.getOpenAt())
				|| request.issueEndAt().isAfter(event.getCloseAt())) {
			throw new GeneralException(CouponErrorCode.ISSUE_PERIOD_OUT_OF_EVENT_PERIOD);
		}
	}

	private void validateDiscountPolicy(CouponCreateRequest request) {
		if (request.discountType() == DiscountType.RATE
				&& (request.discountValue() > 100
						|| (request.maxDiscountAmount() != null && request.maxDiscountAmount() <= 0))) {
			throw new GeneralException(CouponErrorCode.INVALID_RATE_DISCOUNT_POLICY);
		}

		if (request.discountType() == DiscountType.FIXED_AMOUNT
				&& request.maxDiscountAmount() != null) {
			throw new GeneralException(CouponErrorCode.INVALID_FIXED_AMOUNT_DISCOUNT_POLICY);
		}
	}
}
