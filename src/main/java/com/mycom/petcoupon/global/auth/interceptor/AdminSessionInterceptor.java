package com.mycom.petcoupon.global.auth.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.mycom.petcoupon.global.auth.annotation.NoAdminSession;
import com.mycom.petcoupon.global.auth.service.AdminSessionService;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * /admin/** 요청의 세션 토큰을 검증한다.
 *
 * 필터가 아니라 인터셉터인 이유는 DispatcherServlet 안쪽이라서다. 여기서 던진
 * GeneralException은 GlobalExceptionHandler가 받아 다른 API와 같은 CustomResponse
 * 형식으로 나간다. 필터였다면 이 경로만 에러 JSON을 직접 만들어야 했다.
 */
@Component
@RequiredArgsConstructor
public class AdminSessionInterceptor implements HandlerInterceptor {

	public static final String HEADER = "X-ADMIN-KEY";

	private final AdminSessionService adminSessionService;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// 정적 리소스나 404 등 컨트롤러 매핑이 없는 요청은 검증할 대상이 아니다.
		if (!(handler instanceof HandlerMethod handlerMethod)) {
			return true;
		}

		if (handlerMethod.hasMethodAnnotation(NoAdminSession.class)) {
			return true;
		}

		if (!adminSessionService.isValid(request.getHeader(HEADER))) {
			// 헤더 누락 / 토큰 불일치 / 만료를 구분하지 않는다.
			// 구분하면 토큰의 존재 여부가 드러난다.
			throw new GeneralException(CommonErrorCode.UNAUTHORIZED);
		}

		return true;
	}
}
