package com.mycom.petcoupon.coupon.issue.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class CouponIssueLuaConfig {

	@Bean
    public DefaultRedisScript<List> couponIssueLuaScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("lua/coupon-issue.lua"));
        script.setResultType(List.class);

        return script;
    }
	
	@Bean
    public DefaultRedisScript<List> couponIssueRestoreLuaScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("lua/coupon-issue-restore.lua"));
        script.setResultType(List.class);

        return script;
    }
	
	@Bean
	public DefaultRedisScript<String> couponIssuePendingToDlqLuaScript() {
		DefaultRedisScript<String> script = new DefaultRedisScript<>();

		script.setLocation(new ClassPathResource("lua/coupon-issue-pending-to-dlq.lua"));
		script.setResultType(String.class);

		return script;
	}
}
