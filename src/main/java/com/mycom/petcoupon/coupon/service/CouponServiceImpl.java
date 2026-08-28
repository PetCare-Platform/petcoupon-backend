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
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
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
	private final CouponIssueLuaService couponIssueLuaService;

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

		// DB에 쿠폰·재고가 저장된 뒤, 발급에 쓰는 Redis 재고 키까지 여기서 함께 세운다.
		// 관리자가 생성 API 한 번으로 발급 준비를 끝내게 하고, DB에는 쿠폰이 있는데 Redis 재고 키가
		// 없어 발급이 STOCK_NOT_INITIALIZED로 튕기는 반쪽 상태를 원천 차단한다(별도 초기화 호출 제거).
		//
		// 트랜잭션 안에서 호출하므로 Redis 초기화가 실패하면 방금 만든 coupon·coupon_stock도 함께
		// 롤백된다 — 관리자는 같은 생성 요청을 다시 보내기만 하면 된다. Redis는 DB 트랜잭션에
		// 참여하지 않으므로, SET이 성공한 직후 DB 커밋이 깨지는 드문 경우엔 존재하지 않을 couponId의
		// 재고 키가 Redis에 남을 수 있으나, 그 키를 읽는 경로(발급·현황 조회) 자체가 없어 무해하다.
		initializeIssueStock(savedCoupon.getCouponId(), savedCouponStock.getTotalQuantity());

		return couponConverter.toCreateResponse(savedCoupon, savedCouponStock);
	}

	// resetIssueState는 신청자·순번 키와 재고 키를 지운 뒤 재고를 totalQuantity로 다시 세우고,
	// 저장된 값을 되읽어 돌려준다. 새로 채번된 couponId라 지울 기존 상태가 없어 이 생성 경로에서
	// 호출해도 안전하다(이미 발급이 진행 중인 쿠폰에 쓰면 상태가 날아가므로 생성 경로 전용).
	// 되읽은 값이 없거나(null) 세팅한 값과 다르면 초기화가 끝나지 않은 것이므로 생성을 롤백한다.
	private void initializeIssueStock(Long couponId, int totalQuantity) {
		Integer redisStock = couponIssueLuaService.resetIssueState(couponId, totalQuantity);

		if (redisStock == null || redisStock != totalQuantity) {
			throw new GeneralException(CouponErrorCode.ISSUE_REDIS_STATE_CLEAR_FAILED);
		}
	}

	@Override
	@Transactional
	public CouponUpdateResponse updateCoupon(Long eventId, Long couponId, CouponUpdateRequest request) {
		if (request.isEmpty()) {
			throw new GeneralException(CouponErrorCode.EMPTY_UPDATE_REQUEST);
		}

		// 두 행 모두 비관적 락으로 잡는다. 스케줄러(activateCoupons)와 발급(increaseIssuedQuantity)이
		// 검증과 flush 사이에 끼어들면 더티체킹 UPDATE가 그 결과를 덮어쓰기 때문이다.
		// 잠그는 순서는 coupon -> coupon_stock으로, 발급 경로(FK 검사 -> 재고 갱신)와 같게 맞춰 데드락을 피한다.
		Coupon coupon = findCouponInEvent(eventId, couponId);
		CouponStock couponStock = couponStockRepository.findByIdForUpdate(couponId)
				.orElseThrow(() -> new GeneralException(CouponErrorCode.COUPON_NOT_FOUND));
		Event event = coupon.getEvent();

		validateEventStatusForUpdate(event);
		validateCouponStatusForUpdate(coupon);
		validateIssueNotStarted(coupon);

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
		Coupon coupon = couponRepository.findByIdForUpdate(couponId)
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

	// status만으로는 부족하다. READY -> ACTIVE 전이는 스케줄러가 최대 60초 늦게 처리하므로,
	// issueStartAt이 지났는데도 아직 READY로 남아 있는 구간에서 이미 발급이 열린 쿠폰이 수정될 수 있다.
	// 시간으로 한 번 더 막는다. 덤으로 activateCoupons(issueStartAt <= now)와 수정 가능 조건이
	// 서로 배타적이 되어 스케줄러와 같은 쿠폰을 두고 경합할 일이 사실상 없어진다.
	private void validateIssueNotStarted(Coupon coupon) {
		if (!coupon.getIssueStartAt().isAfter(couponRepository.findDatabaseNow())) {
			throw new GeneralException(CouponErrorCode.ISSUE_ALREADY_STARTED);
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
