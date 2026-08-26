package com.mycom.petcoupon.global.auth.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * /admin/** 하위지만 세션 검증을 건너뛰는 엔드포인트에 붙인다.
 *
 * 세션 발급(POST /admin/auth/sessions)이 유일한 대상이다. 이걸 막으면
 * 세션을 받으려면 세션이 필요한 순환에 빠진다.
 *
 * WebConfig의 excludePathPatterns를 쓰지 않는 이유는 그게 경로 단위라
 * 같은 경로의 DELETE(세션 폐기)까지 함께 열리기 때문이다. 메서드 단위로
 * 열어야 발급만 통과시키고 폐기는 검증할 수 있다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoAdminSession {
}
