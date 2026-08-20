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
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mycom.petcoupon.coupon.issue.producer.CouponIssueStreamProducer;

@SpringBootTest
public class CouponIssueStreamProducerIntegrationTest {
	
	private static final String STREAM_KEY = "coupon:issue:stream";
	
	@Autowired
    private CouponIssueStreamProducer producer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(STREAM_KEY);
    }
    
    @AfterEach
    void tearDown() {
        redisTemplate.delete(STREAM_KEY);
    }

    @Test
    void 여러_요청이_동시에_Redis_Stream에_저장된다() throws Exception {

        int requestCount = 100;
        int threadCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<RecordId>> futures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            int index = i;

            futures.add(executor.submit(() -> {
                startLatch.await();

                return producer.publish(
                        1L,
                        (long) index + 1,
                        "request-" + index
                );
            }));
        }

        startLatch.countDown();

        List<RecordId> recordIds = new ArrayList<>();

        for (Future<RecordId> future : futures) {
            recordIds.add(future.get());
        }

        executor.shutdown();

        Long streamSize = redisTemplate.opsForStream().size(STREAM_KEY);

        assertThat(streamSize).isEqualTo((long) requestCount);

        assertThat(recordIds)
                .hasSize(requestCount)
                .doesNotHaveDuplicates();
    }
}
