package com.mycom.petcoupon.coupon.controller;

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

import com.mycom.petcoupon.coupon.dto.res.CouponIssueDlqAbandonResponse;
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

		when(couponIssueDlqReprocessService.listDlqMessages()).thenReturn(List.of(response));

		mockMvc.perform(get("/admin/coupon-issue/dlq"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSuccess").value(true))
				.andExpect(jsonPath("$.result[0].messageId").value(1L))
				.andExpect(jsonPath("$.result[0].requestId").value("request-1"));
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
