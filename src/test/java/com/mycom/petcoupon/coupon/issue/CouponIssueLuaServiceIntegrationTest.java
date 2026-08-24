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

import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;
import com.mycom.petcoupon.global.common.exception.GeneralException;

@SpringBootTest
public class CouponIssueLuaServiceIntegrationTest {

	private static final Long COUPON_ID = 1L;
	private static final String STOCK_KEY = "coupon:issue:stock:{1}";
	private static final String APPLICANTS_KEY = "coupon:issue:applicants:{1}";
	private static final String SEQUENCE_KEY = "coupon:issue:sequence:{1}";
	private static final String REQUEST_SEQUENCE_KEY = "coupon:issue:request-sequence:{1}";
		
    @Autowired
    private CouponIssueLuaService couponIssueLuaService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
    	redisTemplate.delete(
    		List.of(
    			STOCK_KEY,
    			APPLICANTS_KEY,
    			SEQUENCE_KEY,
    	        REQUEST_SEQUENCE_KEY
    		)
    	);
    }

    @AfterEach
    void tearDown() {
    	redisTemplate.delete(
        	List.of(
        		STOCK_KEY,
        		APPLICANTS_KEY,
        		SEQUENCE_KEY,
                REQUEST_SEQUENCE_KEY
        	)
        );
    }

    @Test
    void 재고가_있으면_재고를_차감하고_신청자를_등록한다() {
        redisTemplate.opsForValue().set(STOCK_KEY, "1");

        CouponIssueLuaResult result = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(result.status()).isEqualTo(CouponIssueLuaResultStatus.SUCCESS);
        assertThat(result.sequenceNo()).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("0");
        assertThat(
        	redisTemplate.opsForHash().get(APPLICANTS_KEY, "10")
        ).isEqualTo("request-1");
        
        assertThat(      		
        	redisTemplate.opsForHash().get(REQUEST_SEQUENCE_KEY, "request-1")
        ).isEqualTo("1");
    }

    @Test
    void 동일_사용자가_다시_신청하면_중복으로_처리한다() {
        redisTemplate.opsForValue().set(STOCK_KEY, "10");

        couponIssueLuaService.issue(COUPON_ID, 10L, "request-1");

        CouponIssueLuaResult result = couponIssueLuaService.issue(
            COUPON_ID,
            10L, 
            "request-2"
        );

        assertThat(result.status()).isEqualTo(CouponIssueLuaResultStatus.ALREADY_APPLIED);
        assertThat(result.sequenceNo()).isNull();
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("9");
    }

    @Test
    void 재고가_없으면_품절로_처리한다() {
        redisTemplate.opsForValue().set(STOCK_KEY, "0");

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

        redisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(stock));

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
            assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("0");
            assertThat(redisTemplate.opsForValue().get(SEQUENCE_KEY)).isEqualTo(String.valueOf(stock));
            assertThat(redisTemplate.opsForHash().size(APPLICANTS_KEY)).isEqualTo((long) stock);
            assertThat(redisTemplate.opsForHash().size(REQUEST_SEQUENCE_KEY)).isEqualTo((long) stock);

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
        redisTemplate.opsForValue().set(STOCK_KEY, "10");

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

        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("9");
        assertThat(redisTemplate.opsForValue().get(SEQUENCE_KEY)).isEqualTo("1");
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
        assertThat(redisTemplate.hasKey(APPLICANTS_KEY)).isFalse();
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
            APPLICANTS_KEY,
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
}
