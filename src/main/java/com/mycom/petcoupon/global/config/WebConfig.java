package com.mycom.petcoupon.global.config;

import org.springframework.context.annotation.Configuration;
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
}
