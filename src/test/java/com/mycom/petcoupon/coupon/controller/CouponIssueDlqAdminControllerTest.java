package com.mycom.petcoupon.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.coupon.dto.req.CouponPageRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqAbandonResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqPageResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqReprocessResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.service.CouponIssueDlqReprocessService;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class CouponIssueDlqAdminControllerTest {

	@Mock
	private CouponIssueDlqReprocessService couponIssueDlqReprocessService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new CouponIssueDlqAdminController(couponIssueDlqReprocessService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void listDlqMessages는_목록을_반환한다() throws Exception {
		CouponIssueDlqResponse response = CouponIssueDlqResponse.builder()
				.messageId(1L)
				.couponId(10L)
				.userId(100L)
				.requestId("request-1")
				.retryCount(3)
				.build();

		when(couponIssueDlqReprocessService.listDlqMessages(any(CouponPageRequest.class)))
				.thenReturn(CouponIssueDlqPageResponse.builder()
						.content(List.of(response))
						.page(0).size(20).totalElements(1).totalPages(1)
						.first(true).last(true)
						.build());

		mockMvc.perform(get("/admin/coupon-issue/dlq"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.content[0].messageId").value(1L))
				.andExpect(jsonPath("$.result.content[0].requestId").value("request-1"))
				.andExpect(jsonPath("$.result.totalElements").value(1))
				.andExpect(jsonPath("$.result.page").value(0))
				.andExpect(jsonPath("$.result.size").value(20));
	}

	// [PR 리뷰 반영] 페이지네이션(#174) — page/size 쿼리 파라미터가 CouponPageRequest로
	// 파싱돼 서비스에 그대로 전달되는지 확인한다. 생략하면 CouponPageRequest의 기본값
	// (0, 20)이 쓰이는 것까지 같이 검증한다.
	@Test
	void listDlqMessages는_page_size_파라미터를_CouponPageRequest로_변환해_서비스에_전달한다() throws Exception {
		when(couponIssueDlqReprocessService.listDlqMessages(any(CouponPageRequest.class)))
				.thenReturn(CouponIssueDlqPageResponse.builder()
						.content(List.of())
						.page(0).size(20).totalElements(0).totalPages(0)
						.first(true).last(true)
						.build());

		mockMvc.perform(get("/admin/coupon-issue/dlq")
						.param("page", "2")
						.param("size", "50"))
				.andExpect(status().isOk());

		verify(couponIssueDlqReprocessService).listDlqMessages(eq(new CouponPageRequest(2, 50)));
	}

	@Test
	void listDlqMessages는_page_size가_없으면_기본값을_사용한다() throws Exception {
		when(couponIssueDlqReprocessService.listDlqMessages(any(CouponPageRequest.class)))
				.thenReturn(CouponIssueDlqPageResponse.builder()
						.content(List.of())
						.page(0).size(20).totalElements(0).totalPages(0)
						.first(true).last(true)
						.build());

		mockMvc.perform(get("/admin/coupon-issue/dlq"))
				.andExpect(status().isOk());

		verify(couponIssueDlqReprocessService).listDlqMessages(eq(new CouponPageRequest(0, 20)));
	}

	// AdminCouponQueryControllerTest가 이미 CouponPageRequest 자체의 검증 규칙(음수 page,
	// 지원 안 하는 size 등)을 충분히 검증한다 — 여기서 또 반복 안 한다. 이 컨트롤러에서
	// 새로 확인할 건 "잘못된 값이면 여기서도 똑같이 400·COUPON400-11로 막히는지"뿐이다.
	@Test
	void listDlqMessages는_잘못된_size면_400을_반환한다() throws Exception {
		mockMvc.perform(get("/admin/coupon-issue/dlq")
						.param("size", "25"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COUPON400-11"));

		// CouponPageRequest.from()이 컨트롤러 메서드 진입 즉시 던지므로, 서비스까지
		// 아예 안 내려가야 한다.
		verifyNoInteractions(couponIssueDlqReprocessService);
	}

	@Test
	void reprocess는_재발행_결과를_반환한다() throws Exception {
		CouponIssueDlqReprocessResponse response = CouponIssueDlqReprocessResponse.builder()
				.messageId(1L)
				.requestId("request-1")
				.build();

		when(couponIssueDlqReprocessService.reprocess(1L)).thenReturn(response);

		mockMvc.perform(post("/admin/coupon-issue/dlq/{messageId}/reprocess", 1L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.messageId").value(1L))
				.andExpect(jsonPath("$.result.requestId").value("request-1"));
	}

	@Test
	void reprocess는_DLQ_메시지가_없으면_404를_반환한다() throws Exception {
		when(couponIssueDlqReprocessService.reprocess(1L))
				.thenThrow(new GeneralException(CouponErrorCode.DLQ_MESSAGE_NOT_FOUND));

		mockMvc.perform(post("/admin/coupon-issue/dlq/{messageId}/reprocess", 1L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COUPON404-3"));
	}

	@Test
	void abandon는_재고_복구_결과를_반환한다() throws Exception {
		CouponIssueDlqAbandonResponse response = CouponIssueDlqAbandonResponse.builder()
				.messageId(1L)
				.requestId("request-1")
				.restoreStatus("RESTORED")
				.remainingStock(9)
				.build();

		when(couponIssueDlqReprocessService.abandon(1L)).thenReturn(response);

		mockMvc.perform(post("/admin/coupon-issue/dlq/{messageId}/abandon", 1L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result.messageId").value(1L))
				.andExpect(jsonPath("$.result.restoreStatus").value("RESTORED"))
				.andExpect(jsonPath("$.result.remainingStock").value(9));
	}

	@Test
	void abandon는_DLQ_메시지가_없으면_404를_반환한다() throws Exception {
		when(couponIssueDlqReprocessService.abandon(1L))
				.thenThrow(new GeneralException(CouponErrorCode.DLQ_MESSAGE_NOT_FOUND));

		mockMvc.perform(post("/admin/coupon-issue/dlq/{messageId}/abandon", 1L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.isSuccess").value(false))
				.andExpect(jsonPath("$.code").value("COUPON404-3"));
	}
}
