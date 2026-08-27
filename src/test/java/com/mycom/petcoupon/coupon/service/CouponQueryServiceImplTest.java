package com.mycom.petcoupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.mycom.petcoupon.coupon.converter.CouponConverter;
import com.mycom.petcoupon.coupon.dto.req.CouponFilterRequest;
import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponPageResponse;
import com.mycom.petcoupon.coupon.entity.Coupon;
import com.mycom.petcoupon.coupon.entity.CouponStock;
import com.mycom.petcoupon.coupon.entity.enums.CouponStatus;
import com.mycom.petcoupon.coupon.entity.enums.DiscountType;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.repository.CouponWithStock;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@ExtendWith(MockitoExtension.class)
class CouponQueryServiceImplTest {

    private static final Long EVENT_ID = 1L;
    private static final CouponFilterRequest NO_FILTER = new CouponFilterRequest(null, null);
    private static final CouponPageRequest DEFAULT_PAGE = new CouponPageRequest(0, 20);

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private EventRepository eventRepository;

    // 변환 규칙까지 함께 검증하려고 실제 Converter를 쓴다(상태가 없어 Mock으로 둘 이유가 없다).
    @Spy
    private CouponConverter couponConverter = new CouponConverter();

    @InjectMocks
    private CouponQueryServiceImpl couponQueryService;

    @Test
    void getCouponsReturnsDbStockAndEventInfo() {
        Page<CouponWithStock> page = new PageImpl<>(List.of(couponWithStock()), PageRequest.of(0, 20), 1);
        when(couponRepository.findCouponPage(eq(null), eq(null), any(Pageable.class))).thenReturn(page);

        CouponPageResponse response = couponQueryService.getCoupons(NO_FILTER, DEFAULT_PAGE);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).eventId()).isEqualTo(EVENT_ID);
        assertThat(response.content().get(0).eventName()).isEqualTo("여름 이벤트");
        assertThat(response.content().get(0).name()).isEqualTo("여름 정률 쿠폰");
        // 재고는 Redis가 아니라 coupon_stock 값 그대로다.
        assertThat(response.content().get(0).totalQuantity()).isEqualTo(100);
        assertThat(response.content().get(0).issuedQuantity()).isZero();
        assertThat(response.content().get(0).remainingQuantity()).isEqualTo(100);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }

    @Test
    void getCouponsPassesRequestedPageAndFiltersToRepository() {
        when(eventRepository.existsById(EVENT_ID)).thenReturn(true);
        when(couponRepository.findCouponPage(eq(EVENT_ID), eq(CouponStatus.READY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

        couponQueryService.getCoupons(
                new CouponFilterRequest(EVENT_ID, CouponStatus.READY),
                new CouponPageRequest(2, 50)
        );

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(couponRepository).findCouponPage(eq(EVENT_ID), eq(CouponStatus.READY), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    // eventId 미지정은 전체 조회다. 이벤트 존재 검증까지 타면 안 된다.
    @Test
    void getCouponsSkipsEventValidationWhenEventIdIsNull() {
        when(couponRepository.findCouponPage(eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        couponQueryService.getCoupons(NO_FILTER, DEFAULT_PAGE);

        verifyNoInteractions(eventRepository);
    }

    @Test
    void getCouponsThrowsEventNotFoundWhenEventIdDoesNotExist() {
        when(eventRepository.existsById(999L)).thenReturn(false);
        CouponFilterRequest filter = new CouponFilterRequest(999L, null);

        assertThatThrownBy(() -> couponQueryService.getCoupons(filter, DEFAULT_PAGE))
                .isInstanceOf(GeneralException.class)
                .extracting(exception -> ((GeneralException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);

        verify(couponRepository, never()).findCouponPage(any(), any(), any(Pageable.class));
    }

    @Test
    void getCouponsReturnsEmptyPageWhenNoCouponMatches() {
        when(couponRepository.findCouponPage(eq(null), eq(CouponStatus.SOLD_OUT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        CouponPageResponse response = couponQueryService.getCoupons(
                new CouponFilterRequest(null, CouponStatus.SOLD_OUT),
                DEFAULT_PAGE
        );

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    // 조회 실패를 그대로 흘리면 COMMON500-0으로 뭉뚱그려진다. 목록 조회 실패로 구분해서 던진다.
    @Test
    void getCouponsThrowsCouponListQueryFailedWhenRepositoryFails() {
        when(couponRepository.findCouponPage(eq(null), eq(null), any(Pageable.class)))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> couponQueryService.getCoupons(NO_FILTER, DEFAULT_PAGE))
                .isInstanceOf(GeneralException.class)
                .extracting(exception -> ((GeneralException) exception).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_LIST_QUERY_FAILED);
    }

    // 이벤트 존재 확인도 같은 요청의 DB 조회다. 여기서 터졌다고 COMMON500-0으로 갈리면 안 된다.
    @Test
    void getCouponsThrowsCouponListQueryFailedWhenEventLookupFails() {
        when(eventRepository.existsById(EVENT_ID)).thenThrow(new QueryTimeoutException("timeout"));
        CouponFilterRequest filter = new CouponFilterRequest(EVENT_ID, null);

        assertThatThrownBy(() -> couponQueryService.getCoupons(filter, DEFAULT_PAGE))
                .isInstanceOf(GeneralException.class)
                .extracting(exception -> ((GeneralException) exception).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_LIST_QUERY_FAILED);

        verify(couponRepository, never()).findCouponPage(any(), any(), any(Pageable.class));
    }

    // 없는 이벤트는 DB 장애가 아니라 요청 문제다. try 안으로 옮겨도 404가 500으로 바뀌면 안 된다.
    @Test
    void getCouponsKeepsEventNotFoundOutsideQueryFailure() {
        when(eventRepository.existsById(999L)).thenReturn(false);
        CouponFilterRequest filter = new CouponFilterRequest(999L, null);

        assertThatThrownBy(() -> couponQueryService.getCoupons(filter, DEFAULT_PAGE))
                .isInstanceOf(GeneralException.class)
                .extracting(exception -> ((GeneralException) exception).getErrorCode())
                .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
    }

    private CouponWithStock couponWithStock() {
        Event event = Event.builder()
                .name("여름 이벤트")
                .openAt(LocalDateTime.of(2026, 8, 20, 0, 0))
                .closeAt(LocalDateTime.of(2026, 8, 31, 23, 59))
                .build();
        // 식별자는 DB가 채우는 값이라 엔티티에 setter가 없다. 테스트에서만 리플렉션으로 넣는다.
        ReflectionTestUtils.setField(event, "eventId", EVENT_ID);

        Coupon coupon = Coupon.builder()
                .event(event)
                .name("여름 정률 쿠폰")
                .discountType(DiscountType.RATE)
                .discountValue(20)
                .minOrderAmount(30_000)
                .maxDiscountAmount(10_000)
                .issueStartAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .issueEndAt(LocalDateTime.of(2026, 8, 30, 23, 59))
                .validDays(7)
                .build();
        ReflectionTestUtils.setField(coupon, "couponId", 10L);

        CouponStock couponStock = CouponStock.builder()
                .coupon(coupon)
                .totalQuantity(100)
                .build();

        return new CouponWithStock(coupon, couponStock);
    }
}
