package com.mycom.petcoupon.coupon.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mycom.petcoupon.coupon.service.CouponExpireBatchService;
import com.mycom.petcoupon.global.common.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminCouponExpireControllerTest {

    @Mock
    private CouponExpireBatchService couponExpireBatchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminCouponExpireController(couponExpireBatchService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void triggerExpireBatch_성공하면_200과_만료건수를_반환한다() throws Exception {
        when(couponExpireBatchService.expireOverdueCoupons()).thenReturn(15);

        mockMvc.perform(post("/admin/coupons/expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.result.expiredCount").value(15))
                .andExpect(jsonPath("$.result.executedAt").exists());

        verify(couponExpireBatchService).expireOverdueCoupons();
    }

    @Test
    void triggerExpireBatch_이미_실행중이면_409를_반환한다() throws Exception {
        when(couponExpireBatchService.expireOverdueCoupons())
                .thenThrow(new com.mycom.petcoupon.global.common.exception.GeneralException(
                        com.mycom.petcoupon.coupon.exception.CouponErrorCode.REQUEST_IN_PROGRESS));

        mockMvc.perform(post("/admin/coupons/expire"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COUPON409-5"));

        verify(couponExpireBatchService).expireOverdueCoupons();
    }
}
