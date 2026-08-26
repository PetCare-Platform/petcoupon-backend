package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueStockRestoreResult;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueStockRestoreStatus;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@SpringBootTest
public class CouponIssueLuaServiceIntegrationTest {

	private static final Long COUPON_ID = 1L;
	
	private static String issueKey(String suffix) {
	    return "coupon:issue:" + suffix + ":{" + COUPON_ID + "}";
	}
		
    @Autowired
    private CouponIssueLuaService couponIssueLuaService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
    	redisTemplate.delete(
    		List.of(
    			issueKey("stock"),
    			issueKey("applicants"),
    			issueKey("sequence"), 
    			issueKey("request-sequence")
    		)
    	);
    }

    @AfterEach
    void tearDown() {
    	redisTemplate.delete(
        	List.of(
        		issueKey("stock"),
        		issueKey("applicants"),
        		issueKey("sequence"), 
        		issueKey("request-sequence")
        	)
        );
    }

    @Test
    void 재고가_있으면_재고를_차감하고_신청자를_등록한다() {
        redisTemplate.opsForValue().set(issueKey("stock"), "1");

        CouponIssueLuaResult result = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(result.status()).isEqualTo(CouponIssueLuaResultStatus.SUCCESS);
        assertThat(result.sequenceNo()).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("0");
        assertThat(
        	redisTemplate.opsForHash().get(issueKey("applicants"), "10")
        ).isEqualTo("request-1");
        
        assertThat(      		
        	redisTemplate.opsForHash().get(issueKey("request-sequence"), "request-1")
        ).isEqualTo("1");
    }

    @Test
    void 동일_사용자가_다시_신청하면_중복으로_처리한다() {
        redisTemplate.opsForValue().set(issueKey("stock"), "10");

        couponIssueLuaService.issue(COUPON_ID, 10L, "request-1");

        CouponIssueLuaResult result = couponIssueLuaService.issue(
            COUPON_ID,
            10L, 
            "request-2"
        );

        assertThat(result.status()).isEqualTo(CouponIssueLuaResultStatus.ALREADY_APPLIED);
        assertThat(result.sequenceNo()).isNull();
        assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("9");
    }

    @Test
    void 재고가_없으면_품절로_처리한다() {
        redisTemplate.opsForValue().set(issueKey("stock"), "0");

        CouponIssueLuaResult result = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(result.status()).isEqualTo(CouponIssueLuaResultStatus.SOLD_OUT);
        assertThat(result.sequenceNo()).isNull();
    }

    @Test
    void 동시_요청이_재고보다_많아도_재고를_초과해_발급하지_않는다() throws Exception {

        int stock = 30;
        int requestCount = 100;

        redisTemplate.opsForValue().set(issueKey("stock"), String.valueOf(stock));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            List<Future<CouponIssueLuaResult>> futures = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                long userId = i + 1L;

                futures.add(executor.submit(() -> {
                    startLatch.await();

                    return couponIssueLuaService.issue(
                        COUPON_ID,
                        userId,
                        "request-" + userId
                    );
                }));
            }

            startLatch.countDown();

            long successCount = 0;
            Set<Long> sequenceNumbers = new HashSet<>();

            for (Future<CouponIssueLuaResult> future : futures) {
            	CouponIssueLuaResult result = future.get();
            	
                if (result.status() == CouponIssueLuaResultStatus.SUCCESS) {
                    successCount++;
                    sequenceNumbers.add(result.sequenceNo());
                }
            }

            assertThat(successCount).isEqualTo(stock);
            assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("0");
            assertThat(redisTemplate.opsForValue().get(issueKey("sequence"))).isEqualTo(String.valueOf(stock));
            assertThat(redisTemplate.opsForHash().size(issueKey("applicants"))).isEqualTo((long) stock);
            assertThat(redisTemplate.opsForHash().size(issueKey("request-sequence"))).isEqualTo((long) stock);

            assertThat(sequenceNumbers).hasSize(stock);
            for (long sequenceNo = 1; sequenceNo <= stock; sequenceNo++) {
                assertThat(sequenceNumbers).contains(sequenceNo);
            }

        } finally {
            executor.shutdownNow();
        }
    }
    
    @Test
    void 같은_요청이_재시도되면_최초_발급_순번을_반환한다() {
        redisTemplate.opsForValue().set(issueKey("stock"), "10");

        CouponIssueLuaResult firstResult = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        CouponIssueLuaResult retryResult = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(firstResult.status()).isEqualTo(CouponIssueLuaResultStatus.SUCCESS);
        assertThat(firstResult.sequenceNo()).isEqualTo(1L);

        assertThat(retryResult.status()).isEqualTo(CouponIssueLuaResultStatus.SAME_REQUEST_RETRY);
        assertThat(retryResult.sequenceNo()).isEqualTo(1L);

        assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("9");
        assertThat(redisTemplate.opsForValue().get(issueKey("sequence"))).isEqualTo("1");
    }
    
    @Test
    void 재고_키가_초기화되지_않으면_미초기화_상태로_처리한다() {

    	CouponIssueLuaResult result = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(result.status()).isEqualTo(CouponIssueLuaResultStatus.STOCK_NOT_INITIALIZED);
        assertThat(result.sequenceNo()).isNull();
        assertThat(redisTemplate.hasKey(issueKey("applicants"))).isFalse();
    }
    
    @Test
    void 유효하지_않은_발급_요청은_예외가_발생한다() {
        assertThatThrownBy(() ->
            couponIssueLuaService.issue(null, 10L, "request-1")
        ).isInstanceOf(GeneralException.class);
    }
    
    @Test
    void 신청_이력은_있지만_순번_이력이_없으면_순번_조회_실패로_처리한다() {
        redisTemplate.opsForHash().put(
        	issueKey("applicants"),
            "10",
            "request-1"
        );

        CouponIssueLuaResult result = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(result.status()).isEqualTo(CouponIssueLuaResultStatus.SEQUENCE_NOT_FOUND);

        assertThat(result.sequenceNo()).isNull();
    }
    
    @Test
    void 재고_키가_있으면_초기화된_상태로_실시간_재고를_조회한다() {
        redisTemplate.opsForValue().set(issueKey("stock"), "7");

        CouponIssueRealtimeStock stock = couponIssueLuaService.getRealtimeStock(COUPON_ID);

        assertThat(stock.initialized()).isTrue();
        assertThat(stock.remainingStock()).isEqualTo(7);
    }

    @Test
    void 재고_키가_없으면_미초기화_상태로_조회한다() {
        CouponIssueRealtimeStock stock = couponIssueLuaService.getRealtimeStock(COUPON_ID);

        assertThat(stock.initialized()).isFalse();
        assertThat(stock.remainingStock()).isZero();
    }

    @Test
    void 재고_값이_숫자가_아니면_조회_실패_예외로_변환한다() {
        redisTemplate.opsForValue().set(issueKey("stock"), "not-a-number");

        assertThatThrownBy(() -> couponIssueLuaService.getRealtimeStock(COUPON_ID))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CouponErrorCode.REALTIME_STOCK_READ_FAILED);
    }

    @Test
    void 쿠폰_발급_Redis_상태를_초기화한다() {
        redisTemplate.opsForValue().set(issueKey("stock"), "10");

        couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        couponIssueLuaService.clearIssueState(COUPON_ID);

        assertThat(redisTemplate.hasKey(issueKey("stock"))).isFalse();
        assertThat(redisTemplate.hasKey(issueKey("applicants"))).isFalse();
        assertThat(redisTemplate.hasKey(issueKey("sequence"))).isFalse();
        assertThat(redisTemplate.hasKey(issueKey("request-sequence"))).isFalse();
    }
    
	@Test
	void 발급_실패_요청의_Redis_재고와_신청정보를_복구한다() {
		
		redisTemplate.opsForValue().set(issueKey("stock"), "10");

		CouponIssueLuaResult issueResult = couponIssueLuaService.issue(
			COUPON_ID, 
			10L, 
			"request-1"
		);

		CouponIssueStockRestoreResult result = couponIssueLuaService.restoreStock(
			COUPON_ID, 
			10L, 
			"request-1", 
			issueResult.sequenceNo()
		);

		assertThat(result.status()).isEqualTo(CouponIssueStockRestoreStatus.RESTORED);

		assertThat(result.remainingStock()).isEqualTo(10);

		assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("10");

		assertThat(redisTemplate.opsForHash().get(issueKey("applicants"), "10")).isNull();

		assertThat(redisTemplate.opsForHash().get(issueKey("request-sequence"), "request-1")).isNull();

		// 전역 순번은 되돌리지 않는다.
		assertThat(redisTemplate.opsForValue().get(issueKey("sequence"))).isEqualTo("1");
	}
    
	@Test
	void 같은_요청을_다시_복구해도_재고는_한번만_증가한다() {
		
		redisTemplate.opsForValue().set(issueKey("stock"), "10");

		CouponIssueLuaResult issueResult = couponIssueLuaService.issue(
			COUPON_ID, 
			10L, 
			"request-1"
		);

		CouponIssueStockRestoreResult first = couponIssueLuaService.restoreStock(
			COUPON_ID, 
			10L, 
			"request-1",
			issueResult.sequenceNo()
		);

		CouponIssueStockRestoreResult retry = couponIssueLuaService.restoreStock(COUPON_ID, 10L, "request-1", issueResult.sequenceNo());

		assertThat(first.status()).isEqualTo(CouponIssueStockRestoreStatus.RESTORED);

		assertThat(retry.status()).isEqualTo(CouponIssueStockRestoreStatus.ALREADY_RESTORED);

		assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("10");
	}
    
	@Test
	void 사용자의_현재_requestId와_복구_requestId가_다르면_복구하지_않는다() {
		
		redisTemplate.opsForValue().set(issueKey("stock"), "10");

		CouponIssueLuaResult issueResult = couponIssueLuaService.issue(
			COUPON_ID,
			10L, 
			"request-1"
		);

		CouponIssueStockRestoreResult result = couponIssueLuaService.restoreStock(
			COUPON_ID, 
			10L, 
			"request-2",
			issueResult.sequenceNo()
		);

		assertThat(result.status()).isEqualTo(CouponIssueStockRestoreStatus.REQUEST_MISMATCH);

		assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("9");

		assertThat(redisTemplate.opsForHash().get(issueKey("applicants"), "10")).isEqualTo("request-1");
		
		assertThat(redisTemplate.opsForHash().get(issueKey("request-sequence"), "request-1")).isEqualTo("1");
	}
	
	@Test
	void 저장된_순번과_복구_순번이_다르면_복구하지_않는다() {
		
		redisTemplate.opsForValue().set(issueKey("stock"), "10");

		couponIssueLuaService.issue(
			COUPON_ID, 
			10L, 
			"request-1"
		);

		CouponIssueStockRestoreResult result = couponIssueLuaService.restoreStock(
			COUPON_ID, 
			10L, 
			"request-1", 
			999L
		);

		assertThat(result.status()).isEqualTo(CouponIssueStockRestoreStatus.INCONSISTENT_STATE);

		assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("9");
		
		assertThat(redisTemplate.opsForHash().get(issueKey("applicants"), "10")).isEqualTo("request-1");

		assertThat(redisTemplate.opsForHash().get(issueKey("request-sequence"), "request-1")).isEqualTo("1");
	}
	
	@Test
	void 재고_키가_없으면_복구하지_않는다() {
		
		CouponIssueStockRestoreResult result = couponIssueLuaService.restoreStock(
			COUPON_ID, 
			10L, 
			"request-1", 
			1L
		);

		assertThat(result.status()).isEqualTo(CouponIssueStockRestoreStatus.STOCK_NOT_INITIALIZED);

		assertThat(result.remainingStock()).isZero();
		assertThat(redisTemplate.hasKey(issueKey("stock"))).isFalse();
	}

	@Test
	void 신청_기록은_있지만_순번_기록이_없으면_복구하지_않는다() {
		
		redisTemplate.opsForValue().set(issueKey("stock"), "9");
		redisTemplate.opsForHash().put(issueKey("applicants"), "10", "request-1");

		CouponIssueStockRestoreResult result = couponIssueLuaService.restoreStock(
			COUPON_ID, 
			10L, 
			"request-1", 
			1L
		);

		assertThat(result.status()).isEqualTo(CouponIssueStockRestoreStatus.INCONSISTENT_STATE);

		assertThat(redisTemplate.opsForValue().get(issueKey("stock"))).isEqualTo("9");

		assertThat(redisTemplate.opsForHash().get(issueKey("applicants"), "10")).isEqualTo("request-1");
	}
}
