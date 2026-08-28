package com.mycom.petcoupon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;
import com.mycom.petcoupon.global.auth.AdminAuthProperties;
import com.mycom.petcoupon.monitoring.config.MonitoringProperties;

@EnableJpaAuditing
@EnableScheduling // CouponExpireBatchService, IdempotencyKeyCleanupScheduler(둘 다 @Scheduled)를 돌리는 데 필요
@SpringBootApplication
@EnableConfigurationProperties({CouponIssueStreamProperties.class, AdminAuthProperties.class, MonitoringProperties.class})
public class PetCouponApplication {

	public static void main(String[] args) {
		SpringApplication.run(PetCouponApplication.class, args);
	}

}
