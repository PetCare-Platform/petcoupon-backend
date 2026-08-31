package com.mycom.petcoupon.coupon.service;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponFilterRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponListResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponPageResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponQueryServiceImpl implements CouponQueryService {

    private final CouponRepository couponRepository;
    private final EventRepository eventRepository;
    private final CouponConverter couponConverter;

    @Override
    @Transactional(readOnly = true)
    public CouponPageResponse getCoupons(CouponFilterRequest filterRequest, CouponPageRequest pageRequest) {
        Pageable pageable = PageRequest.of(pageRequest.page(), pageRequest.size());

        // 이벤트 존재 확인도 같은 요청의 DB 조회라 try 안에 둔다. 밖에 두면 같은 원인(DB 장애)인데도
        // 어느 쿼리에서 터졌느냐에 따라 COMMON500-0과 COUPON500-1로 응답이 갈린다.
        // EVENT_NOT_FOUND는 GeneralException이라 아래 catch에 걸리지 않고 그대로 404로 나간다.
        try {
            validateEventExists(filterRequest.eventId());

            Page<CouponListResponse> responsePage = couponRepository
                    .findCouponPage(filterRequest.eventId(), filterRequest.status(), pageable)
                    .map(row -> couponConverter.toListResponse(row.coupon(), row.couponStock()));

            return CouponPageResponse.from(responsePage);
        } catch (DataAccessException e) {
            log.error("관리자 쿠폰 목록 조회에 실패했습니다.", e);

            throw new GeneralException(CouponErrorCode.COUPON_LIST_QUERY_FAILED);
        }
    }

    // 존재하지 않는 eventId로 필터하면 빈 목록 대신 404로 알린다. 빈 목록은 "그 이벤트에 쿠폰이
    // 아직 없다"와 구분되지 않아, 관리자가 오타 난 eventId를 정상 조회 결과로 오인한다.
    // CouponIssueQueryServiceImpl이 조회 전에 userId를 먼저 검증하는 것과 같은 이유.
    private void validateEventExists(Long eventId) {
        if (eventId == null) {
            return;
        }

        if (!eventRepository.existsById(eventId)) {
            throw new GeneralException(EventErrorCode.EVENT_NOT_FOUND);
        }
    }
}
