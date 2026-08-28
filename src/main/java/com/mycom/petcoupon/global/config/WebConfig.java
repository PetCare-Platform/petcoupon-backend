package com.mycom.petcoupon.global.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mycom.petcoupon.global.auth.interceptor.AdminSessionInterceptor;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 전용 API는 전부 /admin/** 아래에 있어서 패턴 하나로 덮인다.
 *
 * 세션 발급 엔드포인트도 /admin/** 하위지만 excludePathPatterns로 빼지 않는다.
 * 그건 경로 단위라 같은 경로의 DELETE(폐기)까지 열리기 때문이다. 대신
 * 발급 메서드에 @NoAdminSession을 붙여 인터셉터가 메서드 단위로 판단한다.
 *
 * /internal/**(부하 테스트용)은 성격이 달라 이 인증 대상이 아니다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	private final AdminSessionInterceptor adminSessionInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminSessionInterceptor)
				.addPathPatterns("/admin/**");
	}

	@Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://127.0.0.1:3000,http://127.0.0.1:5173}")
	private List<String> allowedOrigins;

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
				.allowedOrigins(allowedOrigins.toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				.exposedHeaders("Idempotency-Key", "X-ADMIN-KEY")
				.allowCredentials(true)
				.maxAge(3600);
	}
}
