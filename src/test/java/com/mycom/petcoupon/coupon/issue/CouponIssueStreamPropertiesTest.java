package com.mycom.petcoupon.coupon.issue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;

class CouponIssueStreamPropertiesTest {

    private final ApplicationContextRunner contextRunner =new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    void batchSize의_기본값은_10이다() {
        contextRunner.run(context -> {
            CouponIssueStreamProperties properties = context.getBean(CouponIssueStreamProperties.class);

            assertThat(properties.getBatchSize()).isEqualTo(10);
        });
    }

    @Test
    void batchSize를_설정값으로_변경할_수_있다() {
        contextRunner
        	.withPropertyValues("coupon.issue.stream.batch-size=100")
        	.run(context -> {
        		CouponIssueStreamProperties properties = context.getBean(CouponIssueStreamProperties.class);
        		assertThat(properties.getBatchSize()).isEqualTo(100);
        	});
    }

    @Test
    void batchSize가_0이면_애플리케이션_컨텍스트_생성에_실패한다() {
        contextRunner
        	.withPropertyValues("coupon.issue.stream.batch-size=0")
        	.run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CouponIssueStreamProperties.class)
    static class TestConfig {
    }
}
