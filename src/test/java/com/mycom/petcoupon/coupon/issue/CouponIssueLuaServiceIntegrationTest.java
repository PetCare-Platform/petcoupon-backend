package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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

import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.coupon.issue.service.CouponIssueLuaService;

@SpringBootTest(properties = {
	"coupon.issue.stream.enabled=false"
})
public class CouponIssueLuaServiceIntegrationTest {

	private static final Long COUPON_ID = 1L;
	private static final String STOCK_KEY = "coupon:issue:stock:{1}";
	private static final String APPLICANTS_KEY = "coupon:issue:applicants:{1}";

    @Autowired
    private CouponIssueLuaService couponIssueLuaService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
    	redisTemplate.delete(
    		List.of(
    			STOCK_KEY,
    			APPLICANTS_KEY
    		)
    	);
    }

    @AfterEach
    void tearDown() {
    	redisTemplate.delete(
        	List.of(
        		STOCK_KEY,
        		APPLICANTS_KEY
        	)
        );
    }

    @Test
    void 재고가_있으면_재고를_차감하고_신청자를_등록한다() {
        redisTemplate.opsForValue().set(STOCK_KEY, "1");

        CouponIssueLuaResultStatus result = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(result).isEqualTo(CouponIssueLuaResultStatus.SUCCESS);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("0");
        assertThat(
        	    redisTemplate.opsForHash().get(APPLICANTS_KEY, "10")
        ).isEqualTo("request-1");
    }

    @Test
    void 동일_사용자가_다시_신청하면_중복으로_처리한다() {
        redisTemplate.opsForValue().set(STOCK_KEY, "10");

        couponIssueLuaService.issue(COUPON_ID, 10L, "request-1");

        CouponIssueLuaResultStatus result = couponIssueLuaService.issue(
            COUPON_ID,
            10L, 
            "request-2"
        );

        assertThat(result).isEqualTo(CouponIssueLuaResultStatus.ALREADY_APPLIED);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("9");
    }

    @Test
    void 재고가_없으면_품절로_처리한다() {
        redisTemplate.opsForValue().set(STOCK_KEY, "0");

        CouponIssueLuaResultStatus result = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(result).isEqualTo(CouponIssueLuaResultStatus.SOLD_OUT);
    }

    @Test
    void 동시_요청이_재고보다_많아도_재고를_초과해_발급하지_않는다() throws Exception {

        int stock = 30;
        int requestCount = 100;

        redisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(stock));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);

        try {
            List<Future<CouponIssueLuaResultStatus>> futures = new ArrayList<>();

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

            for (Future<CouponIssueLuaResultStatus> future : futures) {
                if (future.get() == CouponIssueLuaResultStatus.SUCCESS) {
                    successCount++;
                }
            }

            assertThat(successCount).isEqualTo(stock);
            assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("0");
            assertThat(redisTemplate.opsForHash().size(APPLICANTS_KEY)).isEqualTo((long) stock);

        } finally {
            executor.shutdownNow();
        }
    }
    
    @Test
    void 같은_요청이_재시도되면_동일_요청_재시도로_처리한다() {
        redisTemplate.opsForValue().set(STOCK_KEY, "10");

        couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        CouponIssueLuaResultStatus result = couponIssueLuaService.issue(
            COUPON_ID,
            10L,
            "request-1"
        );

        assertThat(result).isEqualTo(CouponIssueLuaResultStatus.SAME_REQUEST_RETRY);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("9");
    }
}
