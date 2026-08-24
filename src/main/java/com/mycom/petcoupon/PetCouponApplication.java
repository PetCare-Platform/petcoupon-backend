package com.mycom.petcoupon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.mycom.petcoupon.coupon.issue.config.CouponIssueStreamProperties;

@EnableJpaAuditing
@SpringBootApplication
@EnableConfigurationProperties(CouponIssueStreamProperties.class)
public class PetCouponApplication {

	public static void main(String[] args) {
		SpringApplication.run(PetCouponApplication.class, args);
	}

}
