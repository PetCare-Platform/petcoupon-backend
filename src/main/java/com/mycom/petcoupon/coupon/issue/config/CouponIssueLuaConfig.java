package com.mycom.petcoupon.coupon.issue.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class CouponIssueLuaConfig {

	@Bean
    public DefaultRedisScript<Long> couponIssueLuaScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();

        script.setLocation(new ClassPathResource("lua/coupon-issue.lua"));
        script.setResultType(Long.class);

        return script;
    }
}
